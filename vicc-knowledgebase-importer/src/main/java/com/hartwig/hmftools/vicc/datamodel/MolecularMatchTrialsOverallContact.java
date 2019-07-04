package com.hartwig.hmftools.vicc.datamodel;

import org.immutables.value.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Value.Immutable
@Value.Style(passAnnotations = { NotNull.class, Nullable.class })
public abstract class MolecularMatchTrialsOverallContact {

    @Nullable
    public abstract String phone();

    @Nullable
    public abstract String last_name();

    @Nullable
    public abstract String email();

    @Nullable
    public abstract String affiliation();
}
