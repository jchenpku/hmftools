package com.hartwig.hmftools.patientreporter.reportingdb;

import static org.junit.Assert.assertEquals;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import com.google.common.io.Resources;
import com.hartwig.hmftools.patientreporter.ExampleAnalysisTestFactory;
import com.hartwig.hmftools.patientreporter.qcfail.QCFailReason;

import org.junit.Test;

public class ReportingDbTest {

    private static final String REPORT_DATES_TSV = Resources.getResource("reporting_db/reporting_db_example.tsv").getPath();

    private static final String REPORT_BASE_DIR = System.getProperty("user.home") + File.separator + "hmf" + File.separator + "tmp";
    private static final boolean WRITE_TO_TSV = false;

    @Test
    public void canReadReportDatesTsv() throws IOException {
        List<ReportingEntry> reportingEntries = ReportingDb.read(REPORT_DATES_TSV);

        assertEquals(2, reportingEntries.size());

        ReportingEntry reportingEntry1 = reportingEntries.get(0);
        assertEquals("ABCD", reportingEntry1.tumorBarcode());
        assertEquals("sampleId1", reportingEntry1.sampleId());
        assertEquals("22-Oct-2019", reportingEntry1.reportDate());
        assertEquals("sequence_report", reportingEntry1.reportType());
        assertEquals("0.70", reportingEntry1.purity());
        assertEquals("true", reportingEntry1.hasReliableQuality());
        assertEquals("false", reportingEntry1.hasReliablePurity());

        ReportingEntry reportingEntry2 = reportingEntries.get(1);
        assertEquals("EFGH", reportingEntry2.tumorBarcode());
        assertEquals("sampleId2", reportingEntry2.sampleId());
        assertEquals("22-Oct-2019", reportingEntry2.reportDate());
        assertEquals("shallow_seq_low_purity", reportingEntry2.reportType());
        assertEquals("N/A", reportingEntry2.purity());
        assertEquals("N/A", reportingEntry2.hasReliableQuality());
        assertEquals("N/A", reportingEntry2.hasReliablePurity());
    }

    @Test
    public void canWriteReportDatesToTsv() throws IOException {
        if (WRITE_TO_TSV) {
            File reportDatesTsv = new File(REPORT_BASE_DIR + File.separator + "reporting_db_test.tsv");

            if (reportDatesTsv.createNewFile()) {
                BufferedWriter writer = new BufferedWriter(new FileWriter(reportDatesTsv, true));
                writer.write("tumorBarcode\tsampleId\treportDate\treportType\tpurity\thasReliableQuality\thasReliablePurity\n");
                writer.close();
            }

            ReportingDb.addSequenceReportToReportingDb(reportDatesTsv.getPath(), ExampleAnalysisTestFactory.buildCOLO829());

            ReportingDb.addQCFailReportToReportingDb(reportDatesTsv.getPath(),
                    ExampleAnalysisTestFactory.buildQCFailReport("LowMolecularTumorSample", QCFailReason.SHALLOW_SEQ_LOW_PURITY));
        }
    }
}