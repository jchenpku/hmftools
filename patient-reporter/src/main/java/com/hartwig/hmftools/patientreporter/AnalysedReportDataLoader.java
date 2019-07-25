package com.hartwig.hmftools.patientreporter;

import java.io.File;
import java.io.IOException;

import com.hartwig.hmftools.common.actionability.ActionabilityAnalyzer;
import com.hartwig.hmftools.common.actionability.drup.DrupActionabilityModel;
import com.hartwig.hmftools.common.actionability.drup.DrupActionabilityModelFactory;
import com.hartwig.hmftools.patientreporter.summary.SummaryFile;
import com.hartwig.hmftools.patientreporter.summary.SummaryModel;
import com.hartwig.hmftools.patientreporter.variants.driver.DriverGeneViewFactory;
import com.hartwig.hmftools.patientreporter.variants.germline.GermlineReportingFile;
import com.hartwig.hmftools.patientreporter.variants.germline.GermlineReportingModel;

import org.jetbrains.annotations.NotNull;

import htsjdk.samtools.reference.IndexedFastaSequenceFile;

final class AnalysedReportDataLoader {

    private AnalysedReportDataLoader() {
    }

    @NotNull
    static AnalysedReportData buildFromFiles(@NotNull ReportData reportData, @NotNull String knowledgebaseDir,
            @NotNull String fastaFileLocation, @NotNull String germlineGenesCsv, @NotNull String sampleSummaryTsv) throws IOException {
        final ActionabilityAnalyzer actionabilityAnalyzer = ActionabilityAnalyzer.fromKnowledgebase(knowledgebaseDir);

        final GermlineReportingModel germlineReportingModel = GermlineReportingFile.buildFromCsv(germlineGenesCsv);
        final SummaryModel summaryModel = SummaryFile.buildFromTsv(sampleSummaryTsv);

        return ImmutableAnalysedReportData.builder()
                .from(reportData)
                .driverGeneView(DriverGeneViewFactory.create())
                .actionabilityAnalyzer(actionabilityAnalyzer)
                .refGenomeFastaFile(new IndexedFastaSequenceFile(new File(fastaFileLocation)))
                .germlineReportingModel(germlineReportingModel)
                .summaryModel(summaryModel)
                .build();
    }
}
