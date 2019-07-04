package com.hartwig.hmftools.vicc.datamodel;

import org.immutables.value.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Value.Immutable
@Value.Style(passAnnotations = { NotNull.class, Nullable.class })
public abstract class MolecularMatchLocations {

    @Nullable
    public abstract String aminoAcidChange();

    @Nullable
    public abstract String intronNumber();

    @Nullable
    public abstract String exonNumber();

    @NotNull
    public abstract String stop();

    @NotNull
    public abstract String start();

    @NotNull
    public abstract String chr();

    @Nullable
    public abstract String strand();

    @Nullable
    public abstract String alt();

    @Nullable
    public abstract String referenceGenome();

    @Nullable
    public abstract String ref();

    @Nullable
    public abstract String cdna();
}
