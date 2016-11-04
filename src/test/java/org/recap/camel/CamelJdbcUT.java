package org.recap.camel;

import org.apache.activemq.ActiveMQQueueBrowser;
import org.apache.activemq.broker.jmx.DestinationViewMBean;
import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.Component;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.file.FileEndpoint;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.file.GenericFileFilter;
import org.apache.camel.component.jms.JmsQueueEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.apache.commons.io.FilenameUtils;
import org.junit.Test;
import org.recap.BaseTestCase;

import org.recap.ReCAPConstants;
import org.recap.camel.activemq.JmxHelper;
import org.recap.camel.datadump.SolrSearchResultsProcessorForExport;
import org.recap.model.search.SearchRecordsRequest;
import org.recap.repository.BibliographicDetailsRepository;
import org.recap.repository.XmlRecordRepository;
import org.recap.service.DataDumpSolrService;
import org.recap.service.formatter.datadump.MarcXmlFormatterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.jms.QueueBrowser;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by peris on 7/17/16.
 */

public class CamelJdbcUT extends BaseTestCase {

    @Value("${etl.split.xml.tag.name}")
    String xmlTagName;

    @Value("${etl.pool.size}")
    Integer etlPoolSize;

    @Value("${etl.pool.size}")
    Integer etlMaxPoolSize;

    @Value("${etl.max.pool.size}")
    String inputDirectoryPath;

    @Value("${activemq.broker.url}")
    String brokerUrl;

    @Autowired
    JmxHelper jmxHelper;

    @Autowired
    XmlRecordRepository xmlRecordRepository;

    @Autowired
    BibliographicDetailsRepository bibliographicDetailsRepository;

    @Autowired
    DataDumpSolrService dataDumpSolrService;

    @Autowired
    MarcXmlFormatterService marcXmlFormatterService;

    @Autowired
    private ProducerTemplate producer;

    private SolrSearchResultsProcessorForExport solrSearchResultsProcessorForExport;

    private MarcRecordFormatProcessor marcRecordFormatProcessor;

    private MarcXMLFormatProcessor marcXMLFormatProcessor;

    @Test
    public void parseXmlAndInsertIntoDb() throws Exception {


        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                FileEndpoint fileEndpoint = endpoint("file:" + inputDirectoryPath, FileEndpoint.class);
                fileEndpoint.setFilter(new XmlFileFilter());

                from(fileEndpoint)
                        .split()
                        .tokenizeXML(xmlTagName)
                        .streaming()
                        .threads(etlPoolSize, etlMaxPoolSize, "xmlProcessingThread")
                        .process(new XmlProcessor(xmlRecordRepository))
                        .to("jdbc:dataSource");
            }
        });

        java.lang.Thread.sleep(10000);
    }

    class XmlFileFilter implements GenericFileFilter {
        @Override
        public boolean accept(GenericFile file) {
            return FilenameUtils.getExtension(file.getAbsoluteFilePath()).equalsIgnoreCase("xml");
        }
    }


    @Test
    public void exportDataDump() throws Exception {
        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("scsbactivemq:queue:solrInputForDataExportQ")
                        .bean(new SolrSearchResultsProcessorForExport(bibliographicDetailsRepository), "processBibEntities")
                        .to("scsbactivemq:queue:bibEntityForDataExportQ");
            }
        });

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("scsbactivemq:queue:bibEntityForDataExportQ")
                        .bean(new MarcRecordFormatProcessor(marcXmlFormatterService), "processRecords")
                        .to("scsbactivemq:queue:MarcRecordForDataExportQ");

            }
        });

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("scsbactivemq:queue:MarcRecordForDataExportQ")
                        .aggregate(constant(true), new DataExportAggregator()).completionPredicate(new DataExportPredicate(50000))
                        .bean(new MarcXMLFormatProcessor(marcXmlFormatterService),"processMarcXmlString")
                        .to(ReCAPConstants.DATADUMP_FILE_SYSTEM_Q);

            }
        });

        SearchRecordsRequest searchRecordsRequest = new SearchRecordsRequest();
        searchRecordsRequest.setOwningInstitutions(Arrays.asList("CUL"));
        searchRecordsRequest.setCollectionGroupDesignations(Arrays.asList("Shared"));
        searchRecordsRequest.setPageSize(10000);

        long startTime = System.currentTimeMillis();
        Map results = dataDumpSolrService.getResults(searchRecordsRequest);
        long endTime = System.currentTimeMillis();
        System.out.println("Time taken to fetch 10K results for page 0 is : " + (endTime-startTime)/1000 + " seconds " );
        String dateTimeString = getDateTimeString();
        String fileName = "PUL"+ File.separator+ dateTimeString +File.separator+ReCAPConstants.DATA_DUMP_FILE_NAME+ "PUL"+0;
        producer.sendBodyAndHeader("scsbactivemq:queue:solrInputForDataExportQ", results, "fileName", fileName);

        Integer totalPageCount = (Integer) results.get("totalPageCount");
        for(int pageNum = 1; pageNum < totalPageCount; pageNum++){
            searchRecordsRequest.setPageNumber(pageNum);
            startTime = System.currentTimeMillis();
            Map results1 = dataDumpSolrService.getResults(searchRecordsRequest);
            endTime = System.currentTimeMillis();
            System.out.println("Time taken to fetch 10K results for page  : " + pageNum + " is " + (endTime-startTime)/1000 + " seconds " );
            fileName = "PUL"+ File.separator+dateTimeString+File.separator+ReCAPConstants.DATA_DUMP_FILE_NAME+ "PUL"+pageNum;
            producer.sendBodyAndHeader("scsbactivemq:queue:solrInputForDataExportQ", results1, "fileName", fileName);
        }

        while (true) {

        }
    }

    public class DataExportAggregator implements AggregationStrategy {

        @Override
        public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
            if (oldExchange == null) {
                oldExchange = new DefaultExchange(newExchange);
                oldExchange.getIn().setHeaders(newExchange.getIn().getHeaders());
                List<Object> body = new ArrayList<>();
                oldExchange.getIn().setBody(body);
                oldExchange.getExchangeId();
            }
            List body = (List) newExchange.getIn().getBody();
            List oldBody = oldExchange.getIn().getBody(List.class);
            if (null!= oldBody && null!= body) {
                oldBody.addAll(body);
                Object oldBatchSize = oldExchange.getIn().getHeader("batchSize");
                Integer newBatchSize = 0;
                if(null != oldBatchSize){
                    newBatchSize= body.size() + (Integer)oldBatchSize;
                } else {
                    newBatchSize = body.size();
                }
                oldExchange.getIn().setHeader("batchSize", newBatchSize);

                for (String key : newExchange.getProperties().keySet()) {
                    oldExchange.setProperty(key, newExchange.getProperty(key));
                }
            }



            return oldExchange;
        }
    }

    public class DataExportPredicate implements Predicate {

        private Integer batchSize;

        public DataExportPredicate(Integer batchSize) {
            this.batchSize = batchSize;
        }

        @Override
        public boolean matches(Exchange exchange) {
           Integer batchSize = (Integer) exchange.getIn().getHeader("batchSize");

            DestinationViewMBean solrInputForDataExportQ = jmxHelper.getBeanForQueueName("solrInputForDataExportQ");
            DestinationViewMBean bibEntityForDataExportQ = jmxHelper.getBeanForQueueName("bibEntityForDataExportQ");
            DestinationViewMBean marcRecordForDataExportQ = jmxHelper.getBeanForQueueName("MarcRecordForDataExportQ");

            boolean qEmpty = solrInputForDataExportQ.getQueueSize()==0 && bibEntityForDataExportQ.getQueueSize()==0 && marcRecordForDataExportQ.getQueueSize()==0;

            if(this.batchSize.equals(batchSize) || qEmpty){
               exchange.getIn().setHeader("batchSize", 0);
               return true;
           }
           return false;
        }
    }

    private String getDateTimeString(){
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat(ReCAPConstants.DATE_FORMAT_DDMMMYYYYHHMM);
        return sdf.format(date);
    }
}
