package com.hartwig.hmftools.patientreporter.report.data;

import static com.hartwig.hmftools.patientreporter.util.PatientReportFormat.exonDescription;

import com.hartwig.hmftools.svannotation.annotations.GeneFusion;
import com.hartwig.hmftools.svannotation.annotations.Transcript;

import org.immutables.value.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Value.Immutable
@Value.Style(allParameters = true,
             passAnnotations = { NotNull.class, Nullable.class })
public abstract class GeneFusionData {

    public abstract String geneStart();

    public abstract String geneContextStart();

    public abstract String geneEnd();

    public abstract String geneContextEnd();

    public abstract String copies();

    @NotNull
    public static GeneFusionData from(@NotNull final GeneFusion fusion) {
        final Transcript upstream = fusion.upstreamLinkedAnnotation();
        final Transcript downstream = fusion.downstreamLinkedAnnotation();

        return ImmutableGeneFusionData.builder()
                .geneStart(upstream.geneName())
                .geneContextStart(exonDescription(upstream, true))
                .geneEnd(downstream.geneName())
                .geneContextEnd(exonDescription(downstream, false))
                .copies("1")
                .build();
    }
}