package org.recap.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Created by peris on 8/12/16.
 */
@Component
public class ReportsRouteBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ReportsRouteBuilder.class);

    /**
     * Instantiates a new Reports route builder.
     *
     * @param camelContext    the camel context
     * @param reportProcessor the report processor
     */
    @Autowired
    public ReportsRouteBuilder(CamelContext camelContext, ReportProcessor reportProcessor) {
        try {
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.ETL_REPORT_Q)
                            .routeId(ScsbCommonConstants.REPORT_ROUTE_ID)
                            .process(reportProcessor);
                }
            });
        } catch (Exception e) {
            logger.error(ScsbConstants.ERROR,e);
        }
    }
}
