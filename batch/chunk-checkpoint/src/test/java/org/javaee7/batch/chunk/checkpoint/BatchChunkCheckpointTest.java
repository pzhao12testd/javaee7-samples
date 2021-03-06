package org.javaee7.batch.chunk.checkpoint;

import static java.util.concurrent.TimeUnit.SECONDS;
import static javax.batch.runtime.BatchRuntime.getJobOperator;
import static javax.batch.runtime.BatchStatus.COMPLETED;
import static javax.batch.runtime.Metric.MetricType.COMMIT_COUNT;
import static javax.batch.runtime.Metric.MetricType.READ_COUNT;
import static javax.batch.runtime.Metric.MetricType.WRITE_COUNT;
import static org.javaee7.batch.chunk.checkpoint.MyCheckpointAlgorithm.checkpointCountDownLatch;
import static org.javaee7.util.BatchTestHelper.getMetricsMap;
import static org.jboss.shrinkwrap.api.ArchivePaths.create;
import static org.jboss.shrinkwrap.api.ShrinkWrap.create;
import static org.jboss.shrinkwrap.api.asset.EmptyAsset.INSTANCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Properties;

import javax.batch.operations.JobOperator;
import javax.batch.runtime.JobExecution;
import javax.batch.runtime.Metric;
import javax.batch.runtime.StepExecution;

import org.javaee7.util.BatchTestHelper;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Batch specification provides a Chunk Oriented processing style. This style is defined by enclosing into a
 * transaction a set of reads, process and write operations via +javax.batch.api.chunk.ItemReader+,
 * +javax.batch.api.chunk.ItemProcessor+ and +javax.batch.api.chunk.ItemWriter+. Items are read one at a time, processed
 * and aggregated. The transaction is then committed when the defined +checkpoint-policy+ is triggered.
 *
 * The +checkpoint-policy+ can be defined as +item+ or +custom+. The +item+ policy means the chunk is checkpointed after
 * a specified number of items are processed. The +custom+ policy means the chunk is checkpointed according to a
 * checkpoint algorithm implementation. To use the +custom+ policy you also need to define a +checkpoint-algorithm+
 * element.
 *
 * include::myJob.xml[]
 *
 * A very simple job is defined in the +myJob.xml+ file. Just a single step with a reader, a processor and a writer. For
 * this sample, a custom checkpoint policy is going to be used. The custom policy needs to implement
 * +javax.batch.api.chunk.CheckpointAlgorithm+ or in alternative extend
 * +javax.batch.api.chunk.AbstractCheckpointAlgorithm+ that already provides empty implementations for all methods.
 *
 * include::MyCheckpointAlgorithm[]
 *
 * Note that the behaviour of this custom checkpoint algorithm could also be achieved by using the +item+ policy and
 * defining the +item-count+ element at the +chunk+ level.
 *
 * @author Roberto Cortez
 */
@RunWith(Arquillian.class)
public class BatchChunkCheckpointTest {
    
    /**
     * We're just going to deploy the application as a +web archive+. Note the inclusion of the following files:
     *
     * [source,file]
     * ----
     * /META-INF/batch-jobs/myJob.xml
     * ----
     *
     * The +myJob.xml+ file is needed for running the batch definition.
     */
    @Deployment
    public static WebArchive createDeployment() {
        WebArchive war = create(WebArchive.class)
            .addClass(BatchTestHelper.class)
            .addPackage("org.javaee7.batch.chunk.checkpoint")
            .addAsWebInfResource(INSTANCE, create("beans.xml"))
            .addAsResource("META-INF/batch-jobs/myJob.xml");
        
        System.out.println("\nBatchChunkCheckpointTest test war content: \n" + war.toString(true) + "\n");
        
        return war;
    }

    /**
     * In the test, we're just going to invoke the batch execution and wait for completion. To validate the test
     * expected behaviour we need to query the +javax.batch.runtime.Metric+ object available in the step execution.
     *
     * The batch process itself will read and process 10 elements from numbers  1 to 10, but only write the odd
     * elements. Commits are executed after 5 elements are read by the custom checkpoint algorithm.
     *
     * @throws Exception an exception if the batch could not complete successfully.
     */
    @Test
    public void testBatchChunkCheckpoint() throws Exception {
        
        JobOperator jobOperator = getJobOperator();
        Long executionId = jobOperator.start("myJob", new Properties());
        JobExecution jobExecution = jobOperator.getJobExecution(executionId);
        
        jobExecution = BatchTestHelper.keepTestAlive(jobExecution);

        for (StepExecution stepExecution : jobOperator.getStepExecutions(executionId)) {
            if (stepExecution.getStepName().equals("myStep")) {
                Map<Metric.MetricType, Long> metricsMap = getMetricsMap(stepExecution.getMetrics());

                // <1> The read count should be 10 elements. Check +MyItemReader+.
                assertEquals(10L, metricsMap.get(READ_COUNT).longValue());
                
                // <2> The write count should be 5. Only half of the elements read are processed to be written.
                assertEquals(10L / 2L, metricsMap.get(WRITE_COUNT).longValue());
                
                // <3> The commit count should be 3. Checkpoint is on every 5th read, plus one final read-commit.
                assertEquals(10L / 5L + 1, metricsMap.get(COMMIT_COUNT).longValue());
            }
        }

        // <4> The checkpoint algorithm should be checked 10 times. One for each element read.
        assertTrue(checkpointCountDownLatch.await(0, SECONDS));
        
        // <5> Job should be completed.
        assertEquals(jobExecution.getBatchStatus(), COMPLETED);
    }
}
