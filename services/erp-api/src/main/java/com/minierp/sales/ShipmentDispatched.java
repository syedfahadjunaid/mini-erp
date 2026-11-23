package com.minierp.sales;

/**
 * Domain event: goods left the warehouse for a customer. Published in the shipment's
 * transaction; the invoicing module listens and issues the invoice after commit.
 *
 * <p>Events carry ids, not entities: they are stored as JSON in the event publication registry
 * and may be delivered again later, so a listener loads the current data itself.
 */
public record ShipmentDispatched(long shipmentId, long salesOrderId, String shipmentNumber) {}
