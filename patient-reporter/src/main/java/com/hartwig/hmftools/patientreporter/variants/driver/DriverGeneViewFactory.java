package com.hartwig.hmftools.patientreporter.variants.driver;

import com.hartwig.hmftools.common.drivercatalog.dnds.DndsDriverGeneLikelihoodSupplier;

import org.jetbrains.annotations.NotNull;

public final class DriverGeneViewFactory {

    private DriverGeneViewFactory() {
    }

    @NotNull
    public static DriverGeneView create() {
        return ImmutableDriverGeneView.of(DndsDriverGeneLikelihoodSupplier.oncoLikelihood().keySet(),
                DndsDriverGeneLikelihoodSupplier.tsgLikelihood().keySet());
    }
}
