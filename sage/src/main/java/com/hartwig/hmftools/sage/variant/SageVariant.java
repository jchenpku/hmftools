package com.hartwig.hmftools.sage.variant;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.hartwig.hmftools.common.genome.position.GenomePosition;
import com.hartwig.hmftools.sage.context.AltContext;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SageVariant implements GenomePosition {

    private final AltContext normal;
    private final Set<String> filters;
    private final SageVariantTier tier;
    private final List<AltContext> tumorAltContexts;
    private final Optional<AltContext> rna;

    private int localPhaseSet;

    public SageVariant(final SageVariantTier tier, @NotNull final Set<String> filters, final AltContext normal,
            final Optional<AltContext> rna, final List<AltContext> tumorAltContexts) {
        assert (!tumorAltContexts.isEmpty());
        this.tier = tier;
        this.normal = normal;
        this.tumorAltContexts = tumorAltContexts;
        this.filters = filters;
        this.rna = rna;
    }

    public boolean isIndel() {
        return normal.ref().length() != normal.alt().length();
    }

    public boolean isInsert() {
        return normal.ref().length() < normal.alt().length();
    }

    public boolean isDelete() {
        return normal.ref().length() > normal.alt().length();
    }

    public int localPhaseSet() {
        return localPhaseSet;
    }

    public void localPhaseSet(int localPhaseSet) {
        this.localPhaseSet = localPhaseSet;
    }

    public boolean isPassing() {
        return filters.isEmpty();
    }

    @NotNull
    public SageVariantTier tier() {
        return tier;
    }

    @NotNull
    public Set<String> filters() {
        return filters;
    }

    @NotNull
    public AltContext normal() {
        return normal;
    }

    @NotNull
    public List<AltContext> tumorAltContexts() {
        return tumorAltContexts;
    }

    @NotNull
    public AltContext primaryTumor() {
        return tumorAltContexts.get(0);
    }

    @NotNull
    @Override
    public String chromosome() {
        return normal.chromosome();
    }

    @Override
    public long position() {
        return normal.position();
    }

    @NotNull
    public Optional<AltContext> rna() {
        return rna;
    }
}
