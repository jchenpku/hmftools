package com.hartwig.hmftools.vicc.datamodel.pmkb;

import java.util.List;

import org.immutables.value.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Value.Immutable
@Value.Style(passAnnotations = { NotNull.class, Nullable.class })
public abstract class PmkbVariant {

    @Nullable
    public abstract String aminoAcidChange();

    @Nullable
    public abstract String germline();

    @Nullable
    public abstract String partnerGene();

    @Nullable
    public abstract String codons();

    @Nullable
    public abstract String description();

    @Nullable
    public abstract String exons();

    @Nullable
    public abstract String notes();

    @Nullable
    public abstract String cosmic();

    @Nullable
    public abstract String effect();

    @Nullable
    public abstract String cnvType();

    @Nullable
    public abstract String id();

    @Nullable
    public abstract String cytoband();

    @Nullable
    public abstract String variantType();

    @Nullable
    public abstract String dnaChange();

    @Nullable
    public abstract String coordinates();

    @Nullable
    public abstract String chromosomeBasedCnv();

    @NotNull
    public abstract List<PmkbGene> gene();

    @Nullable
    public abstract String transcript();

    @Nullable
    public abstract String descriptionType();

    @Nullable
    public abstract String chromosome();

    @Nullable
    public abstract String name();
}