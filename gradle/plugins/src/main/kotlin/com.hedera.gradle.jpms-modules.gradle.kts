/*
 * Copyright (C) 2016-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("org.gradlex.jvm-dependency-conflict-resolution")
    id("org.gradlex.extra-java-module-info")
}

// Fix or enhance the metadata of third-party Modules. This is about the metadata in the
// repositories: '*.pom' and '*.module' files.
jvmDependencyConflicts.patch {
    val grpcModule = "io.helidon.grpc:io.grpc"
    // The following 'io.grpc' libraries are replaced with a singe dependency to
    // 'io.helidon.grpc:io.grpc', which is a re-packaged Modular Jar of all the 'grpc' libraries.
    val grpcComponents = listOf("io.grpc:grpc-api", "io.grpc:grpc-context", "io.grpc:grpc-core")

    // These compile time annotation libraries are not of interest in our setup and are thus removed
    // from the dependencies of all components that bring them in.
    val annotationLibraries =
        listOf(
            "com.google.android:annotations",
            "com.google.code.findbugs:annotations",
            "com.google.code.findbugs:jsr305",
            "com.google.errorprone:error_prone_annotations",
            "com.google.guava:listenablefuture",
            "org.checkerframework:checker-compat-qual",
            "org.checkerframework:checker-qual",
            "org.codehaus.mojo:animal-sniffer-annotations"
        )

    module("io.netty:netty-transport-native-epoll") {
        addFeature("linux-x86_64")
        addFeature("linux-aarch_64")
    }
    module("io.grpc:grpc-netty") {
        annotationLibraries.forEach { removeDependency(it) }
        grpcComponents.forEach { removeDependency(it) }
        addApiDependency(grpcModule)
    }
    module("io.grpc:grpc-protobuf") {
        annotationLibraries.forEach { removeDependency(it) }
        grpcComponents.forEach { removeDependency(it) }
        addApiDependency(grpcModule)
    }
    module("io.grpc:grpc-protobuf-lite") {
        annotationLibraries.forEach { removeDependency(it) }
        grpcComponents.forEach { removeDependency(it) }
        addApiDependency(grpcModule)
        removeDependency("com.google.protobuf:protobuf-javalite")
        addApiDependency("com.google.protobuf:protobuf-java")
    }
    module("io.grpc:grpc-services") {
        annotationLibraries.forEach { removeDependency(it) }
        grpcComponents.forEach { removeDependency(it) }
        addApiDependency(grpcModule)
    }
    module("io.grpc:grpc-stub") {
        annotationLibraries.forEach { removeDependency(it) }
        grpcComponents.forEach { removeDependency(it) }
        addApiDependency(grpcModule)
    }
    module("io.grpc:grpc-testing") {
        annotationLibraries.forEach { removeDependency(it) }
        grpcComponents.forEach { removeDependency(it) }
        addApiDependency(grpcModule)
    }
    module("com.github.ben-manes.caffeine:caffeine") {
        annotationLibraries.forEach { removeDependency(it) }
    }
    module("com.google.dagger:dagger-compiler") {
        annotationLibraries.forEach { removeDependency(it) }
    }
    module("com.google.dagger:dagger-producers") {
        annotationLibraries.forEach { removeDependency(it) }
    }
    module("com.google.dagger:dagger-spi") { annotationLibraries.forEach { removeDependency(it) } }
    module("com.google.guava:guava") {
        (annotationLibraries -
                "com.google.code.findbugs:jsr305" -
                "com.google.errorprone:error_prone_annotations")
            .forEach { removeDependency(it) }
    }
    module("com.google.protobuf:protobuf-java-util") {
        annotationLibraries.forEach { removeDependency(it) }
    }
    module("io.helidon.grpc:io.grpc") { annotationLibraries.forEach { removeDependency(it) } }
    module("org.apache.tuweni:tuweni-bytes") { removeDependency("com.google.code.findbugs:jsr305") }
    module("org.apache.tuweni:tuweni-units") { removeDependency("com.google.code.findbugs:jsr305") }
    module("io.prometheus:simpleclient") {
        removeDependency("io.prometheus:simpleclient_tracer_otel")
        removeDependency("io.prometheus:simpleclient_tracer_otel_agent")
    }
    module("org.jetbrains.kotlin:kotlin-stdlib") {
        removeDependency("org.jetbrains.kotlin:kotlin-stdlib-common")
    }
    module("junit:junit") { removeDependency("org.hamcrest:hamcrest-core") }
    module("org.hyperledger.besu:secp256k1") { addApiDependency("net.java.dev.jna:jna") }
}

// Fix or enhance the 'module-info.class' of third-party Modules. This is about the
// 'module-info.class' inside the Jar files. In our full Java Modules setup every
// Jar needs to have this file. If it is missing, it is added by what is configured here.
extraJavaModuleInfo {
    failOnAutomaticModules = true // Only allow Jars with 'module-info' on all module paths

    module("io.grpc:grpc-netty", "grpc.netty") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requires("java.logging")
    }
    module("io.grpc:grpc-stub", "grpc.stub") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requires("java.logging")
    }
    module("io.grpc:grpc-testing", "grpc.testing")
    module("io.grpc:grpc-services", "grpc.services")
    module("io.grpc:grpc-protobuf", "grpc.protobuf")
    module("io.grpc:grpc-protobuf-lite", "grpc.protobuf.lite")
    module("com.github.spotbugs:spotbugs-annotations", "com.github.spotbugs.annotations")
    module("com.google.code.findbugs:jsr305", "java.annotation") {
        exportAllPackages()
        mergeJar("javax.annotation:javax.annotation-api")
    }
    module("com.google.errorprone:error_prone_annotations", "com.google.errorprone.annotations")
    module("com.google.j2objc:j2objc-annotations", "com.google.j2objc.annotations")
    module("com.google.protobuf:protobuf-java", "com.google.protobuf") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requires("java.logging")
    }
    module("com.google.guava:guava", "com.google.common") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requires("java.logging")
    }
    module("com.google.guava:failureaccess", "com.google.common.util.concurrent.internal")
    module("com.google.api.grpc:proto-google-common-protos", "com.google.api.grpc.common")
    module("com.google.dagger:dagger", "dagger")
    module("io.perfmark:perfmark-api", "io.perfmark")
    module("javax.inject:javax.inject", "javax.inject")
    module("commons-codec:commons-codec", "org.apache.commons.codec")
    module("org.apache.commons:commons-math3", "org.apache.commons.math3")
    module("org.apache.commons:commons-collections4", "org.apache.commons.collections4")
    module("com.esaulpaugh:headlong", "headlong")
    module("org.connid:framework", "org.connid.framework")
    module("org.connid:framework-internal", "org.connid.framework.internal") {
        exportAllPackages()
        requires("org.connid.framework") // this is missing in POM
    }
    module("org.jetbrains:annotations", "org.jetbrains.annotations")
    module("io.tmio:tuweni-units", "tuweni.units")
    module("io.tmio:tuweni-bytes", "tuweni.bytes")
    module("net.i2p.crypto:eddsa", "net.i2p.crypto.eddsa")
    module("io.netty:netty-codec-http", "io.netty.codec.http")
    module("io.netty:netty-codec-http2", "io.netty.codec.http2")
    module("io.netty:netty-codec-socks", "io.netty.codec.socks")
    module("io.netty:netty-handler-proxy", "io.netty.handler.proxy")
    module("io.netty:netty-transport-native-unix-common", "io.netty.transport.unix.common")
    module("io.netty:netty-buffer", "io.netty.buffer")
    module("io.netty:netty-codec", "io.netty.codec")
    module("io.netty:netty-common", "io.netty.common") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requires("java.logging")
        requires("jdk.unsupported")
        ignoreServiceProvider("reactor.blockhound.integration.BlockHoundIntegration")
    }
    module("io.netty:netty-handler", "io.netty.handler")
    module("io.netty:netty-resolver", "io.netty.resolver")
    module("io.netty:netty-transport", "io.netty.transport")
    module("io.netty:netty-transport-classes-epoll", "io.netty.transport.classes.epoll")
    module("org.antlr:antlr4-runtime", "org.antlr.antlr4.runtime")
    module("org.hyperledger.besu.internal:algorithms", "org.hyperledger.besu.internal.crypto")
    module("org.hyperledger.besu.internal:rlp", "org.hyperledger.besu.internal.rlp")
    module("org.hyperledger.besu:arithmetic", "org.hyperledger.besu.nativelib.arithmetic")
    module("org.hyperledger.besu:blake2bf", "org.hyperledger.besu.nativelib.blake2bf")
    module("org.hyperledger.besu:bls12-381", "org.hyperledger.besu.nativelib.bls12_381")
    module("org.hyperledger.besu:besu-datatypes", "org.hyperledger.besu.datatypes")
    module("org.hyperledger.besu:evm", "org.hyperledger.besu.evm") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requiresStatic("com.fasterxml.jackson.annotation")
    }
    module("org.hyperledger.besu:plugin-api", "org.hyperledger.besu.plugin.api")
    module("org.hyperledger.besu:secp256k1", "org.hyperledger.besu.nativelib.secp256k1")
    module("org.hyperledger.besu:secp256r1", "org.hyperledger.besu.nativelib.secp256r1")
    module("com.goterl:resource-loader", "resource.loader")
    module("com.goterl:lazysodium-java", "lazysodium.java")
    module("tech.pegasys:jc-kzg-4844", "tech.pegasys.jckzg4844")
    module("net.java.dev.jna:jna", "com.sun.jna") {
        exportAllPackages()
        requires("java.logging")
    }
    module("org.eclipse.collections:eclipse-collections-api", "org.eclipse.collections.api")
    module("org.eclipse.collections:eclipse-collections", "org.eclipse.collections.impl")
    module("io.prometheus:simpleclient", "io.prometheus.simpleclient")
    module("io.prometheus:simpleclient_common", "io.prometheus.simpleclient_common")
    module("io.prometheus:simpleclient_httpserver", "io.prometheus.simpleclient.httpserver") {
        exportAllPackages()
        requireAllDefinedDependencies()
        requires("jdk.httpserver")
    }

    // Need to use Jar file names here as there is currently no other way to address Jar with
    // classifier directly for patching
    module(
        "netty-transport-native-epoll-4.1.87.Final-linux-x86_64.jar",
        "io.netty.transport.epoll.linux.x86_64"
    )
    module(
        "netty-transport-native-epoll-4.1.87.Final-linux-aarch_64.jar",
        "io.netty.transport.epoll.linux.aarch_64"
    )

    // Annotation processing only
    module("com.google.auto.service:auto-service-annotations", "com.google.auto.service")
    module("com.google.auto.service:auto-service", "com.google.auto.service.processor")
    module("com.google.auto:auto-common", "com.google.auto.common")
    module("com.google.dagger:dagger-compiler", "dagger.compiler")
    module("com.google.dagger:dagger-producers", "dagger.producers")
    module("com.google.dagger:dagger-spi", "dagger.spi")
    module(
        "com.google.devtools.ksp:symbol-processing-api",
        "com.google.devtools.ksp.symbolprocessingapi"
    )
    module("com.google.errorprone:javac-shaded", "com.google.errorprone.javac.shaded")
    module("com.google.googlejavaformat:google-java-format", "com.google.googlejavaformat")
    module("net.ltgt.gradle.incap:incap", "net.ltgt.gradle.incap")
    module("org.jetbrains.kotlinx:kotlinx-metadata-jvm", "kotlinx.metadata.jvm")

    // Testing only
    module("com.google.jimfs:jimfs", "com.google.jimfs")
    module("org.awaitility:awaitility", "awaitility")
    module("uk.org.webcompere:system-stubs-core", "uk.org.webcompere.systemstubs.core")
    module("uk.org.webcompere:system-stubs-jupiter", "uk.org.webcompere.systemstubs.jupiter")

    // JMH only
    module("net.sf.jopt-simple:jopt-simple", "jopt.simple")
    module("org.openjdk.jmh:jmh-core", "jmh.core")
    module("org.openjdk.jmh:jmh-generator-asm", "jmh.generator.asm")
    module("org.openjdk.jmh:jmh-generator-bytecode", "jmh.generator.bytecode")
    module("org.openjdk.jmh:jmh-generator-reflection", "jmh.generator.reflection")

    // Test clients only
    module("com.github.docker-java:docker-java-api", "com.github.docker.java.api")
    module("com.github.docker-java:docker-java-transport", "com.github.docker.java.transport")
    module(
        "com.github.docker-java:docker-java-transport-zerodep",
        "com.github.docker.transport.zerodep"
    )
    module("com.google.protobuf:protobuf-java-util", "com.google.protobuf.util")
    module("com.squareup:javapoet", "com.squareup.javapoet") {
        exportAllPackages()
        requires("java.compiler")
    }
    module("junit:junit", "junit")
    module("org.hamcrest:hamcrest", "org.hamcrest")
    module("org.json:json", "org.json")
    module("org.mockito:mockito-core", "org.mockito")
    module("org.objenesis:objenesis", "org.objenesis")
    module("org.rnorth.duct-tape:duct-tape", "org.rnorth.ducttape")
    module("org.testcontainers:junit-jupiter", "org.testcontainers.junit.jupiter")
    module("org.testcontainers:testcontainers", "org.testcontainers")
    module("org.mockito:mockito-junit-jupiter", "org.mockito.junit.jupiter")
}
