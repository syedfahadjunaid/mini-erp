/**
 * Building blocks every module may use: errors, money and quantity arithmetic, the signed-in
 * {@link com.minierp.shared.Actor}, security and time configuration.
 *
 * <p>Marked as an OPEN application module, so Spring Modulith lets every other module depend
 * on all of it. Nothing in here depends on a business module.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.minierp.shared;

import org.springframework.modulith.ApplicationModule;
