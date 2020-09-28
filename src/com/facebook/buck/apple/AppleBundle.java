/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule
    implements NativeTestable, BuildRuleWithBinary, HasRuntimeDeps, BinaryBuildRule {

  private static final Logger LOG = Logger.get(AppleBundle.class);
  public static final String CODE_SIGN_ENTITLEMENTS = "CODE_SIGN_ENTITLEMENTS";
  private static final String CODE_SIGN_DRY_RUN_ARGS_FILE = "BUCK_code_sign_args.plist";

  @AddToRuleKey private final String extension;

  @AddToRuleKey private final Optional<String> productName;

  @AddToRuleKey private final Optional<SourcePath> maybeEntitlementsFile;

  @AddToRuleKey private final boolean incrementalBundlingEnabled;

  @AddToRuleKey private final BuildRule binary;

  @AddToRuleKey private final Boolean isLegacyWatchApp;

  @AddToRuleKey private final Optional<AppleDsym> appleDsym;

  @AddToRuleKey private final AppleBundleDestinations destinations;

  @AddToRuleKey private final AppleBundleResources resources;

  @AddToRuleKey private final ImmutableList<AppleBundlePart> bundleParts;

  @AddToRuleKey private final Tool ibtool;

  @AddToRuleKey private final ImmutableSortedSet<BuildTarget> tests;

  @AddToRuleKey private final ApplePlatform platform;

  @AddToRuleKey private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;

  @AddToRuleKey private final Optional<Tool> codesignAllocatePath;

  @AddToRuleKey private final Tool codesign;

  @AddToRuleKey private final Optional<Tool> swiftStdlibTool;

  @AddToRuleKey private final Tool lipo;

  @AddToRuleKey private final ImmutableList<String> codesignFlags;

  @AddToRuleKey private final Optional<String> codesignIdentitySubjectName;

  @AddToRuleKey private final boolean copySwiftStdlibToFrameworks;

  @AddToRuleKey private final boolean sliceAppPackageSwiftRuntime;
  @AddToRuleKey private final boolean sliceAppBundleSwiftRuntime;

  private final Path sdkPath;

  private final String binaryName;
  private final Path bundleRoot;
  private final Path binaryPath;
  private final Path bundleBinaryPath;

  private final boolean ibtoolModuleFlag;
  private final ImmutableList<String> ibtoolFlags;

  private final boolean cacheable;
  private final boolean verifyResources;

  private final Duration codesignTimeout;
  private final BuildRuleParams buildRuleParams;
  private BuildableSupport.DepsSupplier depsSupplier;

  @AddToRuleKey private final boolean withDownwardApi;

  @AddToRuleKey private final AppleCodeSignType codeSignType;

  @AddToRuleKey private final boolean dryRunCodeSigning;

  @AddToRuleKey private final Optional<SourcePath> maybeCodeSignIdentityFingerprintFile;

  private final Path infoPlistBundlePath;

  @AddToRuleKey private final Optional<SourcePath> maybeProcessedResourcesDir;

  @AddToRuleKey private final Optional<SourcePath> nonProcessedResourcesContentHashesFileSourcePath;

  @AddToRuleKey private final Optional<SourcePath> processedResourcesContentHashesFileSourcePath;

  AppleBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      String extension,
      Optional<String> productName,
      RelPath infoPlistPathRelativeToBundle,
      BuildRule binary,
      Optional<AppleDsym> appleDsym,
      AppleBundleDestinations destinations,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      AppleCxxPlatform appleCxxPlatform,
      Set<BuildTarget> tests,
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      AppleCodeSignType codeSignType,
      boolean cacheable,
      boolean verifyResources,
      ImmutableList<String> codesignFlags,
      Optional<String> codesignIdentity,
      Optional<Boolean> ibtoolModuleFlag,
      ImmutableList<String> ibtoolFlags,
      Duration codesignTimeout,
      boolean copySwiftStdlibToFrameworks,
      boolean sliceAppPackageSwiftRuntime,
      boolean sliceAppBundleSwiftRuntime,
      boolean withDownwardApi,
      Optional<SourcePath> maybeEntitlementsFile,
      boolean dryRunCodeSigning,
      Optional<SourcePath> maybeCodeSignIdentityFingerprintFile,
      Optional<SourcePath> maybeProcessedResourcesDir,
      Optional<SourcePath> nonProcessedResourcesContentHashesFileSourcePath,
      Optional<SourcePath> processedResourcesContentHashesFileSourcePath,
      boolean incrementalBundlingEnabled) {
    super(buildTarget, projectFilesystem);
    this.buildRuleParams = params;
    this.extension = extension;
    this.productName = productName;
    this.binary = binary;
    this.withDownwardApi = withDownwardApi;
    this.maybeEntitlementsFile = maybeEntitlementsFile;
    this.isLegacyWatchApp = AppleBundleSupport.isLegacyWatchApp(extension, binary);
    this.appleDsym = appleDsym;
    this.destinations = destinations;
    this.resources = resources;
    this.bundleParts = bundleParts;
    this.ibtool = appleCxxPlatform.getIbtool();
    this.binaryName = getBinaryName(getBuildTarget(), this.productName);
    this.bundleRoot =
        getBundleRoot(getProjectFilesystem(), getBuildTarget(), this.binaryName, this.extension);
    this.binaryPath = this.destinations.getExecutablesPath().resolve(this.binaryName);
    this.tests = ImmutableSortedSet.copyOf(tests);
    AppleSdk sdk = appleCxxPlatform.getAppleSdk();
    this.platform = sdk.getApplePlatform();
    this.sdkPath = appleCxxPlatform.getAppleSdkPaths().getSdkPath();
    this.cacheable = cacheable;
    this.verifyResources = verifyResources;
    this.codesignFlags = codesignFlags;
    this.codesignIdentitySubjectName = codesignIdentity;
    this.ibtoolModuleFlag = ibtoolModuleFlag.orElse(false);
    this.ibtoolFlags = ibtoolFlags;
    this.dryRunCodeSigning = dryRunCodeSigning;
    this.maybeCodeSignIdentityFingerprintFile = maybeCodeSignIdentityFingerprintFile;
    this.maybeProcessedResourcesDir = maybeProcessedResourcesDir;

    bundleBinaryPath = bundleRoot.resolve(binaryPath);

    this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    this.codeSignType = codeSignType;

    this.codesignAllocatePath = appleCxxPlatform.getCodesignAllocate();
    this.codesign =
        appleCxxPlatform
            .getCodesignProvider()
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());
    this.swiftStdlibTool =
        appleCxxPlatform.getSwiftPlatform().isPresent()
            ? appleCxxPlatform.getSwiftPlatform().get().getSwiftStdlibTool()
            : Optional.empty();
    this.lipo = appleCxxPlatform.getLipo();

    this.codesignTimeout = codesignTimeout;
    this.copySwiftStdlibToFrameworks = copySwiftStdlibToFrameworks;
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, graphBuilder);

    this.sliceAppPackageSwiftRuntime = sliceAppPackageSwiftRuntime;
    this.sliceAppBundleSwiftRuntime = sliceAppBundleSwiftRuntime;
    this.infoPlistBundlePath = bundleRoot.resolve(infoPlistPathRelativeToBundle.getPath());
    this.nonProcessedResourcesContentHashesFileSourcePath =
        nonProcessedResourcesContentHashesFileSourcePath;
    this.processedResourcesContentHashesFileSourcePath =
        processedResourcesContentHashesFileSourcePath;
    this.incrementalBundlingEnabled = incrementalBundlingEnabled;
  }

  private boolean hasBinary() {
    return binary.getSourcePathToOutput() != null;
  }

  public static String getBinaryName(BuildTarget buildTarget, Optional<String> productName) {
    return productName.orElse(buildTarget.getShortName());
  }

  public static Path getBundleRoot(
      ProjectFilesystem filesystem, BuildTarget buildTarget, String binaryName, String extension) {
    return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
        .resolve(binaryName + "." + extension);
  }

  public String getExtension() {
    return extension;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), bundleRoot);
  }

  public Path getInfoPlistPath() {
    return infoPlistBundlePath;
  }

  public Path getUnzippedOutputFilePathToBinary() {
    return this.binaryPath;
  }

  public String getPlatformName() {
    return platform.getName();
  }

  public Optional<AppleDsym> getAppleDsym() {
    return appleDsym;
  }

  public boolean getIsLegacyWatchApp() {
    return isLegacyWatchApp;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier =
        getPreviousBuildHashesSupplier(context, stepsBuilder);

    if (incrementalBundlingEnabled) {
      createBundleRootDirectory(context, stepsBuilder);
    } else {
      stepsBuilder.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), bundleRoot)));
    }

    if (hasBinary()) {
      appendCopyDsymStep(stepsBuilder, buildableContext, context);
    }

    ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder = ImmutableList.builder();

    if (verifyResources) {
      AppleResourceProcessing.verifyResourceConflicts(
          resources, bundleParts, context.getSourcePathResolver(), destinations);
    }

    if (maybeProcessedResourcesDir.isPresent()) {
      if (incrementalBundlingEnabled) {
        ImmutableSortedMap.Builder<RelPath, String> newContentHashesBuilder =
            ImmutableSortedMap.orderedBy(RelPath.comparator());
        Supplier<ImmutableMap<RelPath, String>> newContentHashesSupplier =
            newContentHashesBuilder::build;
        addStepsToComputeNewContentHashes(
            context.getSourcePathResolver(), stepsBuilder, newContentHashesBuilder);
        appendWriteNewContentHashesStep(
            stepsBuilder, context, buildableContext, newContentHashesSupplier);
      }

      Optional<Supplier<ImmutableMap<RelPath, String>>> maybeNewContentHashesSupplier =
          getCurrentBuildHashesSupplier(context, buildableContext, stepsBuilder);

      AppleResourceProcessing.addStepsToCopyResources(
          context,
          stepsBuilder,
          codeSignOnCopyPathsBuilder,
          resources,
          bundleParts,
          bundleRoot,
          destinations,
          getProjectFilesystem(),
          maybeProcessedResourcesDir.get(),
          oldContentHashesSupplier,
          maybeNewContentHashesSupplier);
    } else {
      AppleResourceProcessing.deprecated_addStepsToCopyResources(
          context,
          stepsBuilder,
          codeSignOnCopyPathsBuilder,
          resources,
          bundleParts,
          bundleRoot,
          destinations,
          getProjectFilesystem(),
          ibtoolFlags,
          isLegacyWatchApp,
          platform,
          LOG,
          ibtool,
          ibtoolModuleFlag,
          getBuildTarget(),
          Optional.of(binaryName),
          withDownwardApi);

      AppleResourceProcessing.deprecated_addVariantFileProcessingSteps(
          resources,
          context,
          bundleRoot,
          destinations,
          stepsBuilder,
          getProjectFilesystem(),
          ibtoolFlags,
          isLegacyWatchApp,
          platform,
          LOG,
          ibtool,
          ibtoolModuleFlag,
          getBuildTarget(),
          Optional.of(binaryName),
          withDownwardApi);
    }

    if (codeSignType != AppleCodeSignType.SKIP) {
      Supplier<CodeSignIdentity> codeSignIdentitySupplier =
          appendStepsToSelectCodeSignIdentity(context, stepsBuilder);

      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(destinations.getFrameworksPath()),
          dryRunCodeSigning ? Optional.empty() : Optional.of(codeSignIdentitySupplier),
          stepsBuilder,
          false);

      ImmutableList<Path> codeSignOnCopyPaths = codeSignOnCopyPathsBuilder.build();

      Optional<Path> entitlementsPlist =
          maybeEntitlementsFile.map(
              sourcePath -> context.getSourcePathResolver().getAbsolutePath(sourcePath).getPath());

      if (dryRunCodeSigning) {
        final boolean shouldUseEntitlements = entitlementsPlist.isPresent();
        appendDryCodeSignSteps(
            stepsBuilder, codeSignOnCopyPaths, codeSignIdentitySupplier, shouldUseEntitlements);
      } else {
        appendCodeSignSteps(
            context,
            stepsBuilder,
            codeSignOnCopyPaths,
            codeSignIdentitySupplier,
            entitlementsPlist);
      }
    } else {
      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(destinations.getFrameworksPath()),
          Optional.empty(),
          stepsBuilder,
          false);
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput()).getPath());

    return stepsBuilder.build();
  }

  @Nonnull
  private Optional<Supplier<ImmutableMap<RelPath, String>>> getCurrentBuildHashesSupplier(
      BuildContext context,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> stepsBuilder) {
    if (incrementalBundlingEnabled) {
      ImmutableSortedMap.Builder<RelPath, String> newContentHashesBuilder =
          ImmutableSortedMap.orderedBy(RelPath.comparator());
      Optional<Supplier<ImmutableMap<RelPath, String>>> maybeNewContentHashesSupplier =
          Optional.of(newContentHashesBuilder::build);
      addStepsToComputeNewContentHashes(
          context.getSourcePathResolver(), stepsBuilder, newContentHashesBuilder);
      appendWriteNewContentHashesStep(
          stepsBuilder, context, buildableContext, maybeNewContentHashesSupplier.get());
      return maybeNewContentHashesSupplier;
    } else {
      return Optional.empty();
    }
  }

  private void createBundleRootDirectory(
      BuildContext context, ImmutableList.Builder<Step> stepsBuilder) {
    BuildCellRelativePath bundleRootPath =
        BuildCellRelativePath.fromCellRelativePath(
            context.getBuildCellRootPath(), getProjectFilesystem(), bundleRoot);
    if (incrementalBundlingEnabled) {
      stepsBuilder.add(MkdirStep.of(bundleRootPath));
    } else {
      stepsBuilder.addAll(MakeCleanDirectoryStep.of(bundleRootPath));
    }
  }

  @Nonnull
  private Supplier<ImmutableMap<RelPath, String>> getPreviousBuildHashesSupplier(
      BuildContext context, ImmutableList.Builder<Step> stepsBuilder) {
    ImmutableMap.Builder<RelPath, String> oldContentHashesBuilder = ImmutableMap.builder();
    Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier =
        oldContentHashesBuilder::build;
    if (incrementalBundlingEnabled) {
      appendReadExistingContentHashesStep(
          stepsBuilder,
          AbsPath.of(context.getBuildCellRootPath().resolve(getContentHashesFilePath())),
          oldContentHashesBuilder);
    }
    return oldContentHashesSupplier;
  }

  private void appendReadExistingContentHashesStep(
      ImmutableList.Builder<Step> stepsBuilder,
      AbsPath contentHashesFilePath,
      ImmutableMap.Builder<RelPath, String> bundlePartPathToHash) {
    stepsBuilder.add(
        new AppleBundleContentHashesReadStep(
            getProjectFilesystem(), contentHashesFilePath, bundlePartPathToHash));
  }

  private void addStepsToComputeNewContentHashes(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableSortedMap.Builder<RelPath, String> newContentHashesBuilder) {

    List<DirectoryContentAppleBundlePart> directoriesWithContentBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryContentAppleBundlePart)
            .map(p -> (DirectoryContentAppleBundlePart) p)
            .collect(Collectors.toList());

    directoriesWithContentBundleParts.forEach(
        bundlePart -> {
          AbsPath hashesFilePath =
              sourcePathResolver.getAbsolutePath(
                  bundlePart
                      .getContentHashSourcePath()
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Parameter should be present when incremental bundling is enabled")));
          ImmutableMap.Builder<RelPath, String> hashesBuilder = ImmutableMap.builder();
          stepsBuilder.add(
              new AppleReadHashPerFileStep(
                  "read-container-directories-bundle-part-hashes", hashesFilePath, hashesBuilder));
          stepsBuilder.add(
              new AbstractExecutionStep("memoize-container-directories-bundle-part-hashes") {
                @Override
                public StepExecutionResult execute(StepExecutionContext context) {
                  RelPath bundleDestinationPath =
                      RelPath.of(bundlePart.getDestination().getPath(destinations));
                  hashesBuilder
                      .build()
                      .forEach(
                          (path, hash) ->
                              newContentHashesBuilder.put(
                                  bundleDestinationPath.resolve(path), hash));
                  return StepExecutionResults.SUCCESS;
                }
              });
        });

    List<FileAppleBundlePart> fileBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof FileAppleBundlePart)
            .map(p -> (FileAppleBundlePart) p)
            .collect(Collectors.toList());

    fileBundleParts.forEach(
        bundlePart -> {
          AppleBundleComponentCopySpec copySpec =
              new AppleBundleComponentCopySpec(bundlePart, sourcePathResolver, destinations);
          appendStepToReadSavedHashFromDisk(
              stepsBuilder,
              sourcePathResolver,
              bundlePart
                  .getContentHashSourcePath()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Parameter should be present when incremental bundling is enabled")),
              copySpec.getDestinationPathRelativeToBundleRoot(),
              newContentHashesBuilder,
              getProjectFilesystem());
        });

    List<DirectoryAppleBundlePart> directoryBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryAppleBundlePart)
            .map(p -> (DirectoryAppleBundlePart) p)
            .collect(Collectors.toList());

    directoryBundleParts.forEach(
        bundlePart -> {
          AppleBundleComponentCopySpec copySpec =
              new AppleBundleComponentCopySpec(bundlePart, sourcePathResolver, destinations);
          appendStepToReadSavedHashFromDisk(
              stepsBuilder,
              sourcePathResolver,
              bundlePart
                  .getContentHashSourcePath()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Parameter should be present when incremental bundling is enabled")),
              copySpec.getDestinationPathRelativeToBundleRoot(),
              newContentHashesBuilder,
              getProjectFilesystem());
        });

    AbsPath processedResourcesContentHashesFilePath =
        sourcePathResolver.getAbsolutePath(
            processedResourcesContentHashesFileSourcePath.orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parameter should be present when incremental bundling is enabled")));

    stepsBuilder.add(
        new AppleReadHashPerFileStep(
            "read-new-processed-resources-hashes",
            processedResourcesContentHashesFilePath,
            newContentHashesBuilder));

    AbsPath nonProcessedResourcesContentHashesFilePath =
        sourcePathResolver.getAbsolutePath(
            nonProcessedResourcesContentHashesFileSourcePath.orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parameter should be present when incremental bundling is enabled")));

    stepsBuilder.add(
        new AppleReadHashPerFileStep(
            "read-new-non-processed-resources-hashes",
            nonProcessedResourcesContentHashesFilePath,
            newContentHashesBuilder));
  }

  private static void appendStepToReadSavedHashFromDisk(
      ImmutableList.Builder<Step> stepsBuilder,
      SourcePathResolverAdapter sourcePathResolver,
      SourcePath savedHashFileSourcePath,
      RelPath toPath,
      ImmutableSortedMap.Builder<RelPath, String> newContentHashesBuilder,
      ProjectFilesystem projectFilesystem) {
    stepsBuilder.add(
        new AbstractExecutionStep("read-saved-hash-from-disk") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) {
            AbsPath contentHashFilePath =
                sourcePathResolver.getAbsolutePath(savedHashFileSourcePath);
            Optional<String> hash = projectFilesystem.readFileIfItExists(contentHashFilePath);
            newContentHashesBuilder.put(
                toPath,
                hash.orElseThrow(
                    () ->
                        new IllegalStateException(
                            String.format(
                                "Expected file with content hash to exist %s",
                                contentHashFilePath))));

            return StepExecutionResults.SUCCESS;
          }
        });
  }

  private void appendWriteNewContentHashesStep(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildContext context,
      BuildableContext buildableContext,
      Supplier<ImmutableMap<RelPath, String>> contentHashesSupplier) {
    BuildCellRelativePath contentHashesFilePathRelativeToCell =
        BuildCellRelativePath.fromCellRelativePath(
            context.getBuildCellRootPath(), getProjectFilesystem(), getContentHashesFilePath());
    stepsBuilder.add(RmStep.of(contentHashesFilePathRelativeToCell));
    stepsBuilder.add(
        new AppleWriteHashPerFileStep(
            "persist-apple-bundle-hashes",
            contentHashesSupplier,
            getContentHashesFilePath(),
            getProjectFilesystem()));
    buildableContext.recordArtifact(getContentHashesFilePath());
  }

  private Path getContentHashesFilePath() {
    return bundleRoot.getParent().resolve("content_hashes.json");
  }

  private Supplier<CodeSignIdentity> appendStepsToSelectCodeSignIdentity(
      BuildContext context, ImmutableList.Builder<Step> stepsBuilder) {
    if (codeSignType == AppleCodeSignType.ADHOC) {
      CodeSignIdentity identity =
          codesignIdentitySubjectName
              .map(CodeSignIdentity::ofAdhocSignedWithSubjectCommonName)
              .orElse(CodeSignIdentity.AD_HOC);
      return () -> identity;
    } else {
      Path fingerprintPath =
          context
              .getSourcePathResolver()
              .getAbsolutePath(
                  maybeCodeSignIdentityFingerprintFile.orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Code sign identity should be provided when code sign is needed")))
              .getPath();
      CodeSignIdentityHolder selectedCodeSignIdentity = new CodeSignIdentityHolder();
      stepsBuilder.add(
          new CodeSignIdentityFindStep(
              fingerprintPath,
              getProjectFilesystem(),
              codeSignIdentitiesSupplier,
              selectedCodeSignIdentity));
      return () -> selectedCodeSignIdentity.getIdentity().get();
    }
  }

  private void appendDryCodeSignSteps(
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList<Path> codeSignOnCopyPaths,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier,
      boolean shouldUseEntitlements) {
    // It only makes sense to sign files, not directories, via codesign.
    // However, for dry-runs of codesigning, files can be embedded
    // as a separate argument to the real codesign; there's no point in
    // signing these as a result.
    ImmutableList.Builder<Path> extraPathsToSignBuilder = ImmutableList.builder();

    for (Path codeSignOnCopyPath : codeSignOnCopyPaths) {
      // TODO(kelliem) remove this hard-coded check for dylibs once dry-run consumers
      // are more flexible.
      if (codeSignOnCopyPath.toString().endsWith(".dylib")) {
        extraPathsToSignBuilder.add(codeSignOnCopyPath);
        continue;
      }
      final boolean shouldUseEntitlementsForExtraBinary = false;
      stepsBuilder.add(
          new DryCodeSignStep(
              getProjectFilesystem(),
              codeSignOnCopyPath,
              shouldUseEntitlementsForExtraBinary,
              codeSignIdentitySupplier,
              new Pair<>(
                  codeSignOnCopyPath.resolve(CODE_SIGN_DRY_RUN_ARGS_FILE), ImmutableList.of())));
    }
    stepsBuilder.add(
        new DryCodeSignStep(
            getProjectFilesystem(),
            bundleRoot,
            shouldUseEntitlements,
            codeSignIdentitySupplier,
            new Pair<>(
                bundleRoot.resolve(CODE_SIGN_DRY_RUN_ARGS_FILE), extraPathsToSignBuilder.build())));
  }

  private void appendCodeSignSteps(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList<Path> codeSignOnCopyPaths,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier,
      Optional<Path> maybeEntitlementsPath) {
    for (Path codeSignOnCopyPath : codeSignOnCopyPaths) {
      stepsBuilder.add(
          new CodeSignStep(
              getProjectFilesystem(),
              context.getSourcePathResolver(),
              codeSignOnCopyPath,
              Optional.empty(),
              codeSignIdentitySupplier,
              codesign,
              codesignAllocatePath,
              codesignFlags,
              codesignTimeout,
              withDownwardApi));
    }
    stepsBuilder.add(
        new CodeSignStep(
            getProjectFilesystem(),
            context.getSourcePathResolver(),
            bundleRoot,
            maybeEntitlementsPath,
            codeSignIdentitySupplier,
            codesign,
            codesignAllocatePath,
            codesignFlags,
            codesignTimeout,
            withDownwardApi));
  }

  /** Adds the swift stdlib to the bundle if needed */
  public void addSwiftStdlibStepIfNeeded(
      SourcePathResolverAdapter resolver,
      Path destinationPath,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier,
      ImmutableList.Builder<Step> stepsBuilder,
      boolean isForPackaging) {
    // It's apparently safe to run this even on a non-swift bundle (in that case, no libs
    // are copied over).
    boolean shouldCopySwiftStdlib =
        !extension.equals(AppleBundleExtension.APPEX.fileExtension)
            && (!extension.equals(AppleBundleExtension.FRAMEWORK.fileExtension)
                || copySwiftStdlibToFrameworks);

    if (swiftStdlibTool.isPresent() && shouldCopySwiftStdlib) {
      String tempDirPattern = isForPackaging ? "__swift_packaging_temp__%s" : "__swift_temp__%s";
      RelPath tempPath =
          BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), tempDirPattern);

      stepsBuilder.addAll(MakeCleanDirectoryStep.of(BuildCellRelativePath.of(tempPath)));

      boolean sliceArchitectures =
          (isForPackaging ? sliceAppPackageSwiftRuntime : sliceAppBundleSwiftRuntime);
      stepsBuilder.add(
          new SwiftStdlibStep(
              getProjectFilesystem().getRootPath(),
              tempPath.getPath(),
              sdkPath,
              destinationPath,
              swiftStdlibTool.get().getCommandPrefix(resolver),
              lipo.getCommandPrefix(resolver),
              bundleBinaryPath,
              ImmutableSet.of(
                  bundleRoot.resolve(destinations.getFrameworksPath()),
                  bundleRoot.resolve(destinations.getPlugInsPath())),
              codeSignIdentitySupplier,
              sliceArchitectures,
              withDownwardApi));
    }
  }

  private void appendCopyDsymStep(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    if (appleDsym.isPresent()) {
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              buildContext
                  .getSourcePathResolver()
                  .getAbsolutePath(appleDsym.get().getSourcePathToOutput())
                  .getPath(),
              bundleRoot.getParent(),
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      appendDsymRenameStepToMatchBundleName(stepsBuilder, buildableContext, buildContext);
    }
  }

  private void appendDsymRenameStepToMatchBundleName(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    Preconditions.checkArgument(hasBinary() && appleDsym.isPresent());

    // rename dSYM bundle to match bundle name
    RelPath dsymPath =
        buildContext
            .getSourcePathResolver()
            .getCellUnsafeRelPath(appleDsym.get().getSourcePathToOutput());
    Path dsymSourcePath = bundleRoot.getParent().resolve(dsymPath.getFileName());
    Path dsymDestinationPath =
        bundleRoot
            .getParent()
            .resolve(bundleRoot.getFileName() + "." + AppleBundleExtension.DSYM.fileExtension);
    stepsBuilder.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), dsymDestinationPath),
            true));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dsymSourcePath, dsymDestinationPath));

    String dwarfFilename =
        AppleDsym.getDwarfFilenameForDsymTarget(appleDsym.get().getBuildTarget());

    // rename DWARF file inside dSYM bundle to match bundle name
    Path dwarfFolder = dsymDestinationPath.resolve(AppleDsym.DSYM_DWARF_FILE_FOLDER);
    Path dwarfSourcePath = dwarfFolder.resolve(dwarfFilename);
    Path dwarfDestinationPath = dwarfFolder.resolve(MorePaths.getNameWithoutExtension(bundleRoot));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dwarfSourcePath, dwarfDestinationPath));

    // record dSYM so we can fetch it from cache
    buildableContext.recordArtifact(dsymDestinationPath);
  }

  @Override
  public boolean isTestedBy(BuildTarget testRule) {
    if (tests.contains(testRule)) {
      return true;
    }

    if (binary instanceof NativeTestable) {
      return ((NativeTestable) binary).isTestedBy(testRule);
    }

    return false;
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (binary instanceof NativeTestable) {
      return ((NativeTestable) binary).getPrivateCxxPreprocessorInput(cxxPlatform, graphBuilder);
    }
    return CxxPreprocessorInput.of();
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binary;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    // When "running" an app bundle, ensure debug symbols are available.
    if (binary instanceof HasAppleDebugSymbolDeps) {
      List<BuildRule> symbolDeps =
          ((HasAppleDebugSymbolDeps) binary).getAppleDebugSymbolDeps().collect(Collectors.toList());
      if (!symbolDeps.isEmpty()) {
        return Stream.concat(Stream.of(binary), symbolDeps.stream()).map(BuildRule::getBuildTarget);
      }
    }
    return Stream.empty();
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(PathSourcePath.of(getProjectFilesystem(), bundleBinaryPath)))
        .build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return Stream.concat(depsSupplier.get().stream(), buildRuleParams.getBuildDeps().stream())
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleResolver);
  }

  public boolean isWithDownwardApi() {
    return withDownwardApi;
  }
}
