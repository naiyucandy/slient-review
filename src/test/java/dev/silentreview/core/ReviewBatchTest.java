package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReviewBatchTest {
    private Review.Result result(Review.Status status,boolean finding){
        List<Review.Finding> findings=finding?List.of(new Review.Finding("R","violation","warning","a.java","after",1,1,"bad()","reason","fix","added")):List.of();
        return new Review.Result(findings,List.of(),List.of(),status);
    }
    @Test void allNoApplicableNeverBecomesCleanCompletion(){
        var summary=ReviewBatch.summarize(List.of(result(Review.Status.NOT_APPLICABLE,false),result(Review.Status.NOT_APPLICABLE,false)),0);
        assertEquals(Review.Status.NOT_APPLICABLE,summary.status());assertEquals(0,summary.completed());assertEquals(2,summary.notApplicable());
    }
    @Test void finalCleanRepositoryCannotHideEarlierFindings(){
        var summary=ReviewBatch.summarize(List.of(result(Review.Status.COMPLETED,true),result(Review.Status.COMPLETED,false)),0);
        assertEquals(Review.Status.COMPLETED,summary.status());assertEquals(1,summary.findings());assertEquals(2,summary.completed());
    }
    @Test void preparationFailureDoesNotOverrideSuccessfulOtherRepository(){
        var summary=ReviewBatch.summarize(List.of(result(Review.Status.COMPLETED,false)),1);
        assertEquals(Review.Status.PARTIAL,summary.status());assertEquals(1,summary.failed());
    }
    @Test void failedRepositoryAndNoApplicableOtherRepositoryRemainPartial(){
        var summary=ReviewBatch.summarize(List.of(result(Review.Status.FAILED,false),result(Review.Status.NOT_APPLICABLE,false)),0);
        assertEquals(Review.Status.PARTIAL,summary.status());assertEquals(1,summary.failed());assertEquals(1,summary.notApplicable());
    }
    @Test void onlyFailuresAreFailedAndPartialCoverageRemainsPartial(){
        assertEquals(Review.Status.FAILED,ReviewBatch.summarize(List.of(result(Review.Status.FAILED,false)),1).status());
        assertEquals(Review.Status.PARTIAL,ReviewBatch.summarize(List.of(result(Review.Status.COMPLETED,false),result(Review.Status.PARTIAL,true)),0).status());
    }
    @Test void emptyBatchIsNotApplicableAndCanceledTasksCannotBeReportedAsComplete(){
        assertEquals(Review.Status.NOT_APPLICABLE,ReviewBatch.summarize(List.of(),0).status());
        assertThrows(IllegalArgumentException.class,()->ReviewBatch.summarize(List.of(result(Review.Status.CANCELED,false)),0));
    }
}
