<#
.SYNOPSIS
    The end-to-end demo against the running stack (docker compose up -d --build first).

.DESCRIPTION
    Master data -> purchase order (raised by pam, approved by max) -> goods receipt into a bin
    -> sales order that reserves stock -> a second order refused for lack of stock -> shipment
    -> automatic invoice (PDF) -> low-stock alert -> transfer to a second warehouse -> stock
    report as PDF and Excel -> reconciliation of the stock ledger.

    Each step runs as the demo user whose job it is, so role checks are part of the demo.
    Safe to run repeatedly: every run creates its own warehouse codes, SKU and partners.
    Exported files go to .\out\
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$run = (Get-Date).ToString('MMddHHmmss')
$out = Join-Path (Split-Path -Parent $PSScriptRoot) 'out'
$step = 0
function Step([string]$text) {
    $script:step++
    Write-Host ""
    Write-Host ("{0,2}. {1}" -f $script:step, $text) -ForegroundColor Cyan
}
function Show([string]$text) { Write-Host "    $text" }
function Assert-Equal($expected, $actual, [string]$what) {
    if ("$expected" -ne "$actual") { throw "Demo check failed: $what was '$actual', expected '$expected'" }
}
function Level([long]$warehouseId, [long]$productId) {
    $levels = @(Invoke-Erp walt "/api/stock/levels?warehouseId=$warehouseId&productId=$productId")
    if ($levels.Count -eq 0) { return [pscustomobject]@{ onHand = 0; reserved = 0; available = 0 } }
    return $levels[0]
}

Step "Master data (adam, ADMIN): two warehouses with bins, a product sold by the box, a supplier, a customer"
$main = Invoke-Erp adam /api/warehouses POST @{ code = "MAIN-$run"; name = 'Main warehouse' }
$shelf = Invoke-Erp adam "/api/warehouses/$($main.id)/bins" POST @{ code = 'A-01-01' }
$east = Invoke-Erp adam /api/warehouses POST @{ code = "EAST-$run"; name = 'East depot' }
$product = Invoke-Erp adam /api/products POST @{
    sku = "WIDGET-$run"; name = 'Blue widget'; baseUom = 'EA'; taxRateBp = 2000
    units = @(@{ uom = 'BOX'; baseUnitsPerUom = 12 })
}
$supplier = Invoke-Erp adam /api/suppliers POST @{ code = "ACME-$run"; name = 'Acme Components'; currency = 'EUR'; paymentTermsDays = 30 }
$customer = Invoke-Erp adam /api/customers POST @{
    code = "GLOBEX-$run"; name = 'Globex Retail BV'; addressLine1 = 'Market Square 7'; postalCode = '1012 AB'
    city = 'Amsterdam'; countryCode = 'NL'; currency = 'EUR'; paymentTermsDays = 14
}
Show "warehouses $($main.code) (bins: RECEIVING, A-01-01) and $($east.code); product $($product.sku) (1 BOX = 12 EA)"

Step "Purchase order (pam, PURCHASING): 10 BOX at 45.00 per box, then submit"
$po = Invoke-Erp pam /api/purchase-orders POST @{
    supplierId = $supplier.id; warehouseId = $main.id
    lines = @(@{ productId = $product.id; uom = 'BOX'; quantity = 10; unitPriceMinor = 4500 })
}
$po = Invoke-Erp pam "/api/purchase-orders/$($po.id)/actions/submit" POST
Show "$($po.orderNumber): $($po.status), total $($po.total.formatted) $($po.currency), $($po.lines[0].baseQuantity) EA"

Step "Segregation of duties: pam may not approve (403); max (MANAGER) approves"
$denied = Invoke-ErpRaw pam "/api/purchase-orders/$($po.id)/actions/approve" POST
Assert-Equal 403 $denied.Status 'pam approving'
Show "pam: HTTP $($denied.Status) $($denied.Body.code)"
$po = Invoke-Erp max "/api/purchase-orders/$($po.id)/actions/approve" POST
Show "max: $($po.status), approved by $($po.approvedBy)"

Step "Goods receipt (walt, WAREHOUSE): 10 BOX into bin A-01-01"
$receipt = Invoke-Erp walt "/api/purchase-orders/$($po.id)/receipts" POST @{
    lines = @(@{ lineId = $po.lines[0].id; quantity = 10; binId = $shelf.id })
}
$level = Level $main.id $product.id
Assert-Equal 120 $level.onHand 'on hand after receipt'
Show "$($receipt.receiptNumber): order is now $($receipt.orderStatus); on hand $($level.onHand) EA"

Step "Reorder point (max): alert when available stock of the widget at MAIN is 30 or less"
$rule = Invoke-Erp max "/api/reorder-rules/$($main.id)/$($product.id)" PUT @{ reorderPoint = 30; reorderQuantity = 120 }
Show "reorder point $($rule.reorderPoint), reorder quantity $($rule.reorderQuantity); available now $($rule.available)"

Step "Sales order (sue, SALES): 100 EA at 19.99, confirm = reserve the stock"
$so = Invoke-Erp sue /api/sales-orders POST @{
    customerId = $customer.id; warehouseId = $main.id
    lines = @(@{ productId = $product.id; quantity = 100; unitPriceMinor = 1999 })
}
$so = Invoke-Erp sue "/api/sales-orders/$($so.id)/actions/confirm" POST
$level = Level $main.id $product.id
Assert-Equal 20 $level.available 'available after reservation'
Show "$($so.orderNumber): $($so.status); on hand $($level.onHand), reserved $($level.reserved), available $($level.available)"

Step "A second order for 30 EA can't be confirmed: only 20 are available (422)"
$second = Invoke-Erp sue /api/sales-orders POST @{
    customerId = $customer.id; warehouseId = $main.id
    lines = @(@{ productId = $product.id; quantity = 30; unitPriceMinor = 1999 })
}
$refused = Invoke-ErpRaw sue "/api/sales-orders/$($second.id)/actions/confirm" POST
Assert-Equal 422 $refused.Status 'confirming the second order'
Show "HTTP $($refused.Status) $($refused.Body.code): available $($refused.Body.shortages[0].available), requested $($refused.Body.shortages[0].requested)"
Invoke-Erp sue "/api/sales-orders/$($second.id)/actions/cancel" POST | Out-Null

Step "Shipment (walt): ship all 100 EA; bins are picked automatically"
$shipment = Invoke-Erp walt "/api/sales-orders/$($so.id)/shipments" POST @{
    lines = @(@{ lineId = $so.lines[0].id; quantity = 100 })
}
$level = Level $main.id $product.id
Show "$($shipment.shipmentNumber): picked $($shipment.lines[0].picks[0].quantity) from bin $($shipment.lines[0].picks[0].binId); on hand $($level.onHand), reserved $($level.reserved)"

Step "Invoice: issued automatically by an event listener after the shipment committed (finn, FINANCE)"
$invoice = Wait-Until -What 'the invoice' -Condition {
    $r = Invoke-ErpRaw finn "/api/shipments/$($shipment.id)/invoice"
    if ($r.Status -eq 200) { $r.Body } else { $null }
}
$invoicePdf = Save-ErpFile finn "/api/invoices/$($invoice.id)/pdf" (Join-Path $out "$($invoice.invoiceNumber).pdf")
Show "$($invoice.invoiceNumber): net $($invoice.netTotal.formatted) + tax $($invoice.taxTotal.formatted) = $($invoice.grossTotal.formatted) $($invoice.currency), due $($invoice.dueDate)"
Show "PDF: $($invoicePdf.FullName)"

Step "Low-stock alert: raised by the StockLevelChanged event when available dropped to 20 (<= 30)"
$alert = Wait-Until -What 'the low-stock alert' -Condition {
    @(Invoke-Erp max "/api/alerts?status=OPEN&warehouseId=$($main.id)") | Where-Object { $_.productId -eq $product.id } | Select-Object -First 1
}
Show "alert $($alert.id): $($alert.sku) at $($alert.warehouseCode), available $($alert.availableNow) <= reorder point $($alert.reorderPoint); suggested order $($alert.reorderQuantity) EA"

Step "Transfer (walt): 10 EA from MAIN/A-01-01 to EAST, then receive it at EAST"
$transfer = Invoke-Erp walt /api/transfers POST @{
    fromWarehouseId = $main.id; toWarehouseId = $east.id
    lines = @(@{ productId = $product.id; fromBinId = $shelf.id; quantity = 10 })
}
Show "$($transfer.transferNumber): $($transfer.status); MAIN on hand $((Level $main.id $product.id).onHand), EAST $((Level $east.id $product.id).onHand)"
$transfer = Invoke-Erp walt "/api/transfers/$($transfer.id)/receive" POST @{}
Show "$($transfer.transferNumber): $($transfer.status); MAIN on hand $((Level $main.id $product.id).onHand), EAST $((Level $east.id $product.id).onHand)"

Step "Stock report as PDF and Excel (finn)"
$pdf = Save-ErpFile finn '/api/reports/stock.pdf' (Join-Path $out 'stock-report.pdf')
$xlsx = Save-ErpFile finn '/api/reports/stock.xlsx' (Join-Path $out 'stock-report.xlsx')
$ledger = Save-ErpFile finn "/api/reports/stock-movements.xlsx?warehouseId=$($main.id)" (Join-Path $out 'stock-movements-main.xlsx')
Show "$($pdf.FullName) ($($pdf.Length) bytes)"
Show "$($xlsx.FullName) ($($xlsx.Length) bytes)"
Show "$($ledger.FullName) ($($ledger.Length) bytes)"

Step "Reconciliation (finn): every balance recomputed from the append-only ledger"
$report = Invoke-Erp finn /api/reports/reconciliation
Assert-Equal True $report.balanced 'reconciliation'
Show "balanced: $($report.balanced); $($report.movementsChecked) movements checked; $(@($report.discrepancies).Count) discrepancies"

Write-Host ""
Write-Host "Demo finished. Swagger UI: http://localhost:8780/swagger-ui.html (token: .\scripts\get-token.ps1 walt)" -ForegroundColor Green
