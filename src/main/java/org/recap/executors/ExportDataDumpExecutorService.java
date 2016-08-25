package org.recap.executors;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.recap.ReCAPConstants;
import org.recap.model.etl.ExportDataDumpCallable;
import org.recap.model.export.DataDumpRequest;
import org.recap.model.jaxb.marc.BibRecords;
import org.recap.repository.BibliographicDetailsRepository;
import org.recap.util.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.Date;
import java.util.concurrent.*;

/**
 * Created by premkb on 19/8/16.
 */
@Service
public class ExportDataDumpExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(ExportDataDumpExecutorService.class);

    private ExecutorService executorService;

    @Autowired
    private ProducerTemplate producer;

    @Autowired
    private BibliographicDetailsRepository bibliographicDetailsRepository;

    private StopWatch stopWatch;

    private int limitPage;

    public boolean exportDump(DataDumpRequest dataDumpRequest)throws InterruptedException,ExecutionException{
        boolean successFlag = true;
        try {
            startProcess();
            int noOfThreads = dataDumpRequest.getNoOfThreads();
            int batchSize = dataDumpRequest.getBatchSize();
            Long totalRecordCount = getTotalRecordCount(dataDumpRequest);
            String limitPageString = System.getProperty(ReCAPConstants.DATADUMP_LIMIT_PAGE);
            limitPage = System.getProperty(ReCAPConstants.DATADUMP_LIMIT_PAGE)==null ? 0 : Integer.parseInt(System.getProperty(ReCAPConstants.DATADUMP_LIMIT_PAGE));
            int loopCount = limitPageString == null ? getLoopCount(totalRecordCount,batchSize):(Integer.parseInt(limitPageString));

            if(logger.isInfoEnabled()){
                logger.info("Total no. of records "+totalRecordCount);
                int recordsToExport = 0;
                if (limitPage == 0) {
                    recordsToExport = totalRecordCount.intValue();
                } else {
                    recordsToExport = totalRecordCount > ((limitPage)*batchSize)?((limitPage)*batchSize) : totalRecordCount.intValue();
                }
                logger.info("Total no. of records to be exported based on page limit - "+recordsToExport);
                logger.info("Records per file - "+batchSize);
            }
            setExecutorService(noOfThreads);
            for(int pageNum = 0;pageNum < loopCount;pageNum++){
                StopWatch stopWatchPerFile = new StopWatch();
                stopWatchPerFile.start();
                Callable callable = getExportDataDumpCallable(pageNum,batchSize,dataDumpRequest,bibliographicDetailsRepository);
                BibRecords bibRecords = getExecutorService().submit(callable) == null ? null : (BibRecords)getExecutorService().submit(callable).get();
                String fileName = ReCAPConstants.DATA_DUMP_FILE_NAME + (pageNum+1) + ReCAPConstants.XML_FILE_FORMAT;
                producer.sendBodyAndHeader(ReCAPConstants.DATA_DUMP_Q, bibRecords, "fileName", fileName);
                stopWatchPerFile.stop();
                if(logger.isInfoEnabled()){
                    logger.info("Total time taken to export file no. "+(pageNum+1)+" is "+stopWatchPerFile.getTotalTimeMillis()/1000+" seconds");
                    logger.info("File no. "+(pageNum+1)+" exported");
                }
            }
            getExecutorService().shutdownNow();
            getStopWatch().stop();
            if(logger.isInfoEnabled()){
                logger.info("Total time taken to export all data - "+stopWatch.getTotalTimeMillis()/1000+" seconds ("+stopWatch.getTotalTimeMillis()/60000+" minutes)");
            }
        } catch (IllegalStateException |InterruptedException | ExecutionException | CamelExecutionException e) {
            e.printStackTrace();logger.error(e.getMessage());
            successFlag =false;
        }
        return successFlag;
    }

    private int getLoopCount(Long totalRecordCount,int batchSize){
        int quotient = Integer.valueOf(Long.toString(totalRecordCount)) / (batchSize);
        int remainder = Integer.valueOf(Long.toString(totalRecordCount)) % (batchSize);
        int loopCount = remainder == 0 ? quotient : quotient + 1;
        return loopCount;
    }

    private Long getTotalRecordCount(DataDumpRequest dataDumpRequest){
        Long totalRecordCount;
        Date inputDate = DateUtil.getDateFromString(dataDumpRequest.getDate(), ReCAPConstants.DATE_FORMAT_MMDDYYY);
        if(dataDumpRequest.getFetchType() != null && dataDumpRequest.getFetchType() == 0){
            totalRecordCount = bibliographicDetailsRepository.count();
        }else{
            if(dataDumpRequest.getInstitutionCodes() != null && dataDumpRequest.getDate() == null) {
                totalRecordCount = bibliographicDetailsRepository.countByInstitutionCodes(dataDumpRequest.getInstitutionCodes());
            }else if(dataDumpRequest.getInstitutionCodes() == null && dataDumpRequest.getDate() != null){
                totalRecordCount = bibliographicDetailsRepository.countByLastUpdatedDate(inputDate);
            } else{
                totalRecordCount = bibliographicDetailsRepository.countByInstitutionCodesAndLastUpdatedDate(dataDumpRequest.getInstitutionCodes(), inputDate);
            }
        }
        logger.info("totalRecordCount----->"+totalRecordCount);
        return totalRecordCount;
    }

    private Callable getExportDataDumpCallable(int pageNum,int batchSize,DataDumpRequest dataDumpRequest,BibliographicDetailsRepository bibliographicDetailsRepository){
        return new ExportDataDumpCallable(pageNum,batchSize,dataDumpRequest,bibliographicDetailsRepository);
    }

    private void setExecutorService(Integer numThreads) {
        if (null == executorService || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(numThreads);
        }
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public StopWatch getStopWatch() {
        if (null == stopWatch) {
            stopWatch = new StopWatch();
        }
        return stopWatch;
    }

    private void startProcess() {
        getStopWatch().start();
    }
}
