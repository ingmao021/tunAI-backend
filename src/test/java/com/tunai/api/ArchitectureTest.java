package com.tunai.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** RNF-BE-013: arquitectura hexagonal; las dependencias solo apuntan hacia el dominio. */
@AnalyzeClasses(packages = "com.tunai.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule hexagonalLayers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain")
            .definedBy("com.tunai.api.domain..")
            .layer("Application")
            .definedBy("com.tunai.api.application..")
            .layer("InboundAdapters")
            .definedBy("com.tunai.api.adapter.in..")
            .layer("OutboundAdapters")
            .definedBy("com.tunai.api.adapter.out..")
            .layer("Config")
            .definedBy("com.tunai.api.config..")
            .whereLayer("Config")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("InboundAdapters")
            .mayOnlyBeAccessedByLayers("Config")
            .whereLayer("OutboundAdapters")
            .mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("InboundAdapters", "OutboundAdapters", "Config")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "InboundAdapters", "OutboundAdapters", "Config");

    @ArchTest
    static final ArchRule domainAndApplicationArePureJava = noClasses()
            .that()
            .resideInAnyPackage("com.tunai.api.domain..", "com.tunai.api.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "lombok..", "com.fasterxml..", "tools.jackson..")
            .because("el dominio y la aplicación son Java puro (§13.1)");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnEachOther = noClasses()
            .that()
            .resideInAPackage("com.tunai.api.adapter.out.tripo..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.tunai.api.adapter.out.quota..",
                    "com.tunai.api.adapter.out.concurrency..",
                    "com.tunai.api.adapter.out.metrics..")
            .allowEmptyShould(true);
}
