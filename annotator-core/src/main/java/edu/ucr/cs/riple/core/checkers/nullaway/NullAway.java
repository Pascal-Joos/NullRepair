/*
 * MIT License
 *
 * Copyright (c) 2023 Nima Karimipour
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package edu.ucr.cs.riple.core.checkers.nullaway;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import edu.ucr.cs.riple.annotator.util.io.TSVFiles;
import edu.ucr.cs.riple.core.Context;
import edu.ucr.cs.riple.core.Main;
import edu.ucr.cs.riple.core.checkers.CheckerBaseClass;
import edu.ucr.cs.riple.core.checkers.DiagnosticPosition;
import edu.ucr.cs.riple.core.checkers.nullaway.codefix.AdvancedNullAwayCodeFix;
import edu.ucr.cs.riple.core.checkers.nullaway.codefix.AgentBaselineNullAwayCodeFix;
import edu.ucr.cs.riple.core.checkers.nullaway.codefix.BasicNullAwayCodeFix;
import edu.ucr.cs.riple.core.checkers.nullaway.codefix.ChatGPT;
import edu.ucr.cs.riple.core.checkers.nullaway.codefix.ChatGPTTokenUsage;
import edu.ucr.cs.riple.core.checkers.nullaway.codefix.NullAwayCodeFix;
import edu.ucr.cs.riple.core.module.ModuleConfiguration;
import edu.ucr.cs.riple.core.module.ModuleInfo;
import edu.ucr.cs.riple.core.registries.field.FieldInitializationStore;
import edu.ucr.cs.riple.core.registries.index.Error;
import edu.ucr.cs.riple.core.registries.index.Fix;
import edu.ucr.cs.riple.core.registries.region.Region;
import edu.ucr.cs.riple.core.util.GitUtility;
import edu.ucr.cs.riple.core.util.Utility;
import edu.ucr.cs.riple.core.util.Utility.CommandResult;
import edu.ucr.cs.riple.injector.Printer;
import edu.ucr.cs.riple.injector.changes.AddAnnotation;
import edu.ucr.cs.riple.injector.changes.AddMarkerAnnotation;
import edu.ucr.cs.riple.injector.changes.AddSingleElementAnnotation;
import edu.ucr.cs.riple.injector.changes.AddTypeUseMarkerAnnotation;
import edu.ucr.cs.riple.injector.changes.RegionRewrite;
import edu.ucr.cs.riple.injector.location.Location;
import edu.ucr.cs.riple.injector.location.OnField;
import edu.ucr.cs.riple.injector.location.OnParameter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Represents <a href="https://github.com/uber/NullAway">NullAway</a> checker in Annotator. */
public class NullAway extends CheckerBaseClass<NullAwayError> {

  /**
   * The name of the checker. To select this checker, this name must be used in the configurations.
   */
  public static final String NAME = "NULLAWAY";

  /** Class name for adding cast to nonnull statements. */
  public static final String CAST_TO_NONNULL = "edu.ucr.cs.riple.annotator.util.Nullability";

  public static final String NULL_UNMARKED = "org.jspecify.annotations.NullUnmarked";

  /** Latest supported version of NullAway serialization. */
  public static final int VERSION = 4;

  /**
   * All NullAway serialization versions this Annotator can consume. Version 3 serializes errors as
   * TSV ({@code errors.tsv}); version 4 (introduced in uber/NullAway#1322) switches to XML ({@code
   * errors.xml}) to carry structured auto-fix metadata.
   */
  private static final ImmutableSet<Integer> SUPPORTED_VERSIONS = ImmutableSet.of(3, 4);

  /** The logger instance. */
  private final Logger logger;

  public NullAway(Context context) {
    super(context);
    this.logger = LoggerFactory.getLogger(NullAway.class);
  }

  @Override
  public Set<NullAwayError> deserializeErrors(ModuleInfo module) {
    Set<NullAwayError> errors = new HashSet<>();
    module
        .getModuleConfiguration()
        .forEach(
            configuration -> {
              // Version 4+ serializes errors as XML; earlier versions use TSV. Dispatch on which
              // output file NullAway produced.
              Path xmlPath = configuration.dir.resolve("errors.xml");
              if (Files.exists(xmlPath)) {
                errors.addAll(deserializeErrorsFromXML(module, xmlPath));
              } else {
                errors.addAll(
                    deserializeErrorsFromTSV(module, configuration.dir.resolve("errors.tsv")));
              }
            });
    return errors;
  }

  /**
   * Deserializes errors from a NullAway v3 {@code errors.tsv} file.
   *
   * @param module Module info.
   * @param path Path to the {@code errors.tsv} file.
   * @return Set of deserialized errors.
   */
  private Set<NullAwayError> deserializeErrorsFromTSV(ModuleInfo module, Path path) {
    Set<NullAwayError> errors = new HashSet<>();
    try (BufferedReader br = Files.newBufferedReader(path, Charset.defaultCharset())) {
      String line;
      // Skip header.
      br.readLine();
      while ((line = br.readLine()) != null) {
        errors.add(deserializeErrorFromTSVLine(module, line));
      }
    } catch (IOException e) {
      throw new RuntimeException("Exception happened in reading errors at: " + path, e);
    }
    return errors;
  }

  /**
   * Deserializes errors from a NullAway v4 {@code errors.xml} file. The file is a stream of
   * standalone {@code <error>} fragments with no enclosing root element, so it is wrapped in a
   * synthetic root before parsing.
   *
   * @param module Module info.
   * @param path Path to the {@code errors.xml} file.
   * @return Set of deserialized errors.
   */
  private Set<NullAwayError> deserializeErrorsFromXML(ModuleInfo module, Path path) {
    Set<NullAwayError> errors = new HashSet<>();
    String content;
    try {
      content = Files.readString(path, Charset.defaultCharset());
    } catch (IOException e) {
      throw new RuntimeException("Exception happened in reading errors at: " + path, e);
    }
    if (content.isBlank()) {
      return errors;
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document =
          builder.parse(new InputSource(new StringReader("<errors>" + content + "</errors>")));
      NodeList errorNodes = document.getDocumentElement().getElementsByTagName("error");
      for (int i = 0; i < errorNodes.getLength(); i++) {
        errors.add(deserializeErrorFromXMLElement(module, (Element) errorNodes.item(i)));
      }
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new RuntimeException("Exception happened in parsing errors XML at: " + path, e);
    }
    return errors;
  }

  /**
   * Deserializes an error from a NullAway v4 {@code <error>} XML element.
   *
   * @param moduleInfo Module info.
   * @param error The {@code <error>} element.
   * @return the deserialized error corresponding to the given XML element.
   */
  private NullAwayError deserializeErrorFromXMLElement(ModuleInfo moduleInfo, Element error) {
    String errorType = getDirectChildText(error, "message_type");
    String errorMessage = getDirectChildText(error, "message");
    Region region =
        new Region(getDirectChildText(error, "enc_class"), getDirectChildText(error, "enc_member"));
    int offset = Integer.parseInt(getDirectChildText(error, "offset"));
    Path path = Printer.deserializePath(getDirectChildText(error, "path"));
    Location nonnullTarget = null;
    Element nonnullTargetElement = getDirectChild(error, "nonnull_target");
    if (nonnullTargetElement != null) {
      String[] locationValues =
          new String[] {
            getDirectChildText(nonnullTargetElement, "target_kind"),
            getDirectChildText(nonnullTargetElement, "target_class"),
            getDirectChildText(nonnullTargetElement, "target_method"),
            getDirectChildText(nonnullTargetElement, "target_param"),
            getDirectChildText(nonnullTargetElement, "target_index"),
            getDirectChildText(nonnullTargetElement, "target_path"),
          };
      nonnullTarget = Location.createLocationFromArrayInfo(locationValues);
    }
    return createErrorFromParsedValues(
        moduleInfo,
        errorType,
        errorMessage,
        region,
        offset,
        path,
        nonnullTarget,
        buildInfos(error));
  }

  /**
   * Reconstructs the {@code infos} metadata the LLM codefix consumes from a NullAway v4 {@code
   * <error>} element. NullAway (since the auto-fix-metadata work in uber/NullAway#1322) emits a
   * {@code <nullableExpressionInfo>} element and, for local-variable/method origins, an {@code
   * <origins>} element. These are flattened into a single JSON object matching the shape the former
   * {@code errors.json} format produced: the nullable-expression fields at the top level and the
   * origins as an {@code "origins"} array. Returns an empty object when the element carries
   * neither.
   */
  private static JsonObject buildInfos(Element error) {
    JsonObject infos = new JsonObject();
    Element info = getDirectChild(error, "nullableExpressionInfo");
    if (info != null) {
      copyLeafTextChildren(info, infos);
    }
    Element originsElement = getDirectChild(error, "origins");
    if (originsElement != null) {
      JsonArray origins = new JsonArray();
      NodeList originNodes = originsElement.getChildNodes();
      for (int i = 0; i < originNodes.getLength(); i++) {
        Node node = originNodes.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("origin")) {
          JsonObject origin = new JsonObject();
          copyLeafTextChildren((Element) node, origin);
          origins.add(origin);
        }
      }
      infos.add("origins", origins);
    }
    return infos;
  }

  /**
   * Copies every direct child element of {@code parent} that holds only text (no nested elements)
   * into {@code target} as a string property. Container children such as {@code <location>} are
   * skipped, since the codefix reads only the flat nullable-expression fields.
   */
  private static void copyLeafTextChildren(Element parent, JsonObject target) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element child = (Element) node;
      boolean hasElementChild = false;
      NodeList grandChildren = child.getChildNodes();
      for (int j = 0; j < grandChildren.getLength(); j++) {
        if (grandChildren.item(j).getNodeType() == Node.ELEMENT_NODE) {
          hasElementChild = true;
          break;
        }
      }
      if (!hasElementChild) {
        target.addProperty(child.getNodeName(), child.getTextContent());
      }
    }
  }

  /**
   * Returns the first direct child element of {@code parent} with the given tag name, or {@code
   * null} if none exists.
   */
  private static @Nullable Element getDirectChild(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tag)) {
        return (Element) node;
      }
    }
    return null;
  }

  /**
   * Returns the text content of the first direct child element of {@code parent} with the given tag
   * name, or the literal string {@code "null"} if none exists (matching the placeholder used in the
   * TSV format).
   */
  private static String getDirectChildText(Element parent, String tag) {
    Element child = getDirectChild(parent, tag);
    return child == null ? "null" : child.getTextContent();
  }

  /**
   * Deserializes an error from a TSV line.
   *
   * @param moduleInfo Module info.
   * @param line the TSV line.
   * @return the deserialized error corresponding to the values in the given tsv line.
   */
  private NullAwayError deserializeErrorFromTSVLine(ModuleInfo moduleInfo, String line) {
    String[] values = line.split("\t");
    Preconditions.checkArgument(
        values.length == 12,
        String.format(
            "Expected 12 values to create Error instance in NullAway serialization version 3 but found: %s",
            values.length));
    int offset = Integer.parseInt(values[4]);
    Path path = Printer.deserializePath(values[5]);
    String errorMessage = values[1];
    String errorType = values[0];
    Region region = new Region(values[2], values[3]);
    Location nonnullTarget =
        Location.createLocationFromArrayInfo(Arrays.copyOfRange(values, 6, 12));
    // The v3 TSV format carries no nullable-expression/origin metadata; the LLM codefix falls back
    // to parsing the error message for those versions.
    return createErrorFromParsedValues(
        moduleInfo, errorType, errorMessage, region, offset, path, nonnullTarget, new JsonObject());
  }

  /**
   * Builds a {@link NullAwayError} from the fields common to all serialization versions. Shared by
   * the TSV (v3) and XML (v4) deserialization paths.
   *
   * @param moduleInfo Module info.
   * @param errorType Error type reported by NullAway.
   * @param errorMessage Error message reported by NullAway.
   * @param region Region where the error is reported.
   * @param offset Offset of the program point where the error is reported.
   * @param path Path to the containing source file.
   * @param nonnullTarget Location of the {@code @Nonnull} target of a pseudo-assignment, or {@code
   *     null} if not applicable.
   * @param infos Nullable-expression/origin metadata (empty for the v3 TSV format).
   * @return the deserialized error.
   */
  private NullAwayError createErrorFromParsedValues(
      ModuleInfo moduleInfo,
      String errorType,
      String errorMessage,
      Region region,
      int offset,
      Path path,
      @Nullable Location nonnullTarget,
      JsonObject infos) {
    Context context = moduleInfo.getContext();
    DiagnosticPosition position =
        new DiagnosticPosition(path, offset, context.offsetHandler.getOriginalOffset(path, offset));
    if (nonnullTarget == null
        && errorType.equals(NullAwayError.ErrorType.METHOD_INITIALIZER.type)) {
      Set<AddAnnotation> annotationsOnField =
          computeAddAnnotationInstancesForUninitializedFields(
              errorMessage, region.clazz, moduleInfo);
      return createError(
          errorType, errorMessage, region, path, position, infos, annotationsOnField, moduleInfo);
    }
    if (nonnullTarget != null && nonnullTarget.isOnField()) {
      nonnullTarget = extendVariableList(nonnullTarget.toField(), moduleInfo);
    }
    Set<AddAnnotation> annotations;
    if (nonnullTarget == null) {
      annotations = Set.of();
    } else if (Utility.isTypeUseAnnotation(config.nullableAnnot)) {
      if (errorType.equals(NullAwayError.ASSIGN_NULLABLE_TO_NONNULL_ARRAY)) {
        // The error ASSIGN_NULLABLE_TO_NONNULL_ARRAY from NullAway triggers a fix on an array
        // variable
        // with [1, 0] indicating its component type.
        annotations =
            Set.of(
                new AddTypeUseMarkerAnnotation(
                    nonnullTarget, config.nullableAnnot, ImmutableList.of(ImmutableList.of(1, 0))));
      } else {
        annotations = Set.of(new AddTypeUseMarkerAnnotation(nonnullTarget, config.nullableAnnot));
      }
    } else {
      annotations = Set.of(new AddMarkerAnnotation(nonnullTarget, config.nullableAnnot));
    }
    return createError(
        errorType, errorMessage, region, path, position, infos, annotations, moduleInfo);
  }

  /**
   * Extracts uninitialized field names from the given error message.
   *
   * @param errorMessage Error message.
   * @return Set of uninitialized field names.
   */
  private Set<String> extractUninitializedFieldNames(String errorMessage) {
    String prefix = "initializer method does not guarantee @NonNull field";
    int begin = prefix.length();
    if (errorMessage.charAt(begin) == 's') {
      begin += 1;
    }
    int end = errorMessage.indexOf(" is initialized along");
    end = end == -1 ? errorMessage.indexOf(" are initialized along ") : end;
    if (end == -1) {
      throw new RuntimeException(
          "Error message for initializer error not recognized in version "
              + 3
              + ": "
              + errorMessage);
    }
    String[] fieldsData = errorMessage.substring(begin, end).split(",");
    Set<String> fields =
        Arrays.stream(fieldsData)
            // NullAway serializes line number right after a field name starting with an open
            // parentheses. (e.g. foo (line z)). Since 0.13.8 the name itself is quoted
            // (e.g. 'foo' (line z)). This approach of extracting field names is extremely
            // dependent on the format of NullAway error messages. Should be watched carefully
            // and updated if the format is changed by NullAway (maybe regex?).
            .map(s -> unquote(s.substring(0, s.indexOf("(")).trim()))
            .collect(Collectors.toSet());
    if (fields.isEmpty()) {
      throw new RuntimeException(
          "Could not extract any uninitialized field in message for initializer error in version "
              + 3
              + ": "
              + errorMessage);
    }
    return fields;
  }

  /**
   * Removes the enclosing single quotes NullAway puts around syntax element references in its error
   * messages, leaving an unquoted name untouched.
   *
   * @param name Name as it appears in the error message.
   * @return Name without the enclosing quotes.
   */
  private static String unquote(String name) {
    return name.length() > 1 && name.charAt(0) == '\'' && name.charAt(name.length() - 1) == '\''
        ? name.substring(1, name.length() - 1)
        : name;
  }

  /**
   * Computes a set of {@link AddAnnotation} instances for fields that are uninitialized. This
   * method extracts field names from the provided error message, and for each uninitialized field,
   * it attempts to find the location of the field within the specified class. If a field's location
   * is found, an {@link AddMarkerAnnotation} is created with the appropriate nullable annotation
   * and added to the result set.
   *
   * @param errorMessage the error message containing the details about uninitialized fields.
   * @param encClass The class where this error is reported.
   * @param module the {@link ModuleInfo} containing the field registry and configuration
   *     information.
   * @return an {@link ImmutableSet} of {@link AddAnnotation} instances representing the fields that
   *     should have annotations added, based on their uninitialized status.
   */
  private ImmutableSet<AddAnnotation> computeAddAnnotationInstancesForUninitializedFields(
      String errorMessage, String encClass, ModuleInfo module) {
    return extractUninitializedFieldNames(errorMessage).stream()
        .map(
            field -> {
              OnField locationOnField =
                  module.getFieldRegistry().getLocationOnField(encClass, field);
              if (locationOnField == null) {
                return null;
              }
              return new AddMarkerAnnotation(
                  extendVariableList(locationOnField, module), config.nullableAnnot);
            })
        .filter(Objects::nonNull)
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * Suppresses remaining errors by following steps below:
   *
   * <ul>
   *   <li>Enclosing method of triggered errors will be marked with {@code @NullUnmarked}
   *       annotation.
   *   <li>Uninitialized fields (inline or by constructor) will be annotated as
   *       {@code @SuppressWarnings("NullAway.Init")}.
   *   <li>Explicit {@code Nullable} assignments to fields will be annotated as
   *       {@code @SuppressWarnings("NullAway")}.
   * </ul>
   */
  @Override
  public void suppressRemainingErrors() {
    // Collect regions with remaining errors.
    Utility.buildTarget(context, false);
    Set<NullAwayError> remainingErrors = deserializeErrors(context.targetModuleInfo);
    // Collect all regions for NullUnmarked.
    // For all errors in regions which correspond to a method's body, we can add @NullUnmarked at
    // the method level.
    Set<AddAnnotation> nullUnMarkedAnnotations =
        remainingErrors.stream()
            // find the corresponding method nodes.
            .map(
                error -> {
                  if (error.getRegion().isOnCallable()
                      &&
                      // We suppress initialization errors reported on constructors using
                      // @SuppressWarnings("NullAway.Init"). We add @NullUnmarked on constructors
                      // only for errors in the body of the constructor.
                      error.isNonInitializationError()) {
                    return context
                        .targetModuleInfo
                        .getMethodRegistry()
                        .findMethodByName(error.encClass(), error.encMember());
                  }
                  // For methods invoked in an initialization region, where the error is that
                  // `@Nullable` is being passed as an argument, we add a `@NullUnmarked` annotation
                  // to the called method.
                  if (error.messageType.equals("PASS_NULLABLE")
                      && error.isSingleAnnotationFix()
                      && error.toResolvingLocation().isOnParameter()) {
                    OnParameter nullableParameter = error.toResolvingParameter();
                    return context
                        .targetModuleInfo
                        .getMethodRegistry()
                        .findMethodByName(
                            nullableParameter.clazz, nullableParameter.enclosingMethod.method);
                  }
                  return null;
                })
            // Filter null values from map above.
            .filter(Objects::nonNull)
            .map(node -> new AddMarkerAnnotation(node.location, NULL_UNMARKED))
            .collect(Collectors.toSet());

    // For errors within static initialization blocks, add a @NullUnmarked annotation on the
    // enclosing class
    nullUnMarkedAnnotations.addAll(
        remainingErrors.stream()
            .filter(
                error ->
                    error.getRegion().isOnInitializationBlock()
                        && !error.getRegion().isInAnonymousClass())
            .map(
                error ->
                    new AddMarkerAnnotation(
                        context.targetModuleInfo.getLocationOnClass(error.getRegion().clazz),
                        NULL_UNMARKED))
            .collect(Collectors.toSet()));
    Set<AddAnnotation> result = new HashSet<>(nullUnMarkedAnnotations);

    // Collect suppress warnings, errors on field declaration regions.
    Set<OnField> fieldsWithSuppressWarnings =
        remainingErrors.stream()
            .filter(
                error -> {
                  if (!error.getRegion().isOnField()) {
                    return false;
                  }
                  if (error.messageType.equals("PASS_NULLABLE")) {
                    // It is already resolved with @NullUnmarked selected above.
                    return false;
                  }
                  // We can silence them by SuppressWarnings("NullAway.Init")
                  return error.isNonInitializationError();
                })
            .map(
                error ->
                    context
                        .targetModuleInfo
                        .getFieldRegistry()
                        .getLocationOnField(error.getRegion().clazz, error.getRegion().member))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    Set<AddAnnotation> suppressWarningsAnnotations =
        fieldsWithSuppressWarnings.stream()
            .map(
                onField ->
                    new AddSingleElementAnnotation(onField, "SuppressWarnings", "NullAway", false))
            .collect(Collectors.toSet());
    result.addAll(suppressWarningsAnnotations);

    // Collect NullAway.Init SuppressWarnings
    Set<AddAnnotation> initializationSuppressWarningsAnnotations =
        remainingErrors.stream()
            .filter(
                e ->
                    e.messageType.equals("METHOD_NO_INIT") || e.messageType.equals("FIELD_NO_INIT"))
            .flatMap(Error::getResolvingFixesStream)
            .filter(Fix::isOnField)
            // Filter nodes annotated with SuppressWarnings("NullAway")
            .filter(fix -> !fieldsWithSuppressWarnings.contains(fix.toField()))
            .map(
                fix ->
                    new AddSingleElementAnnotation(
                        fix.toField(), "SuppressWarnings", "NullAway.Init", false))
            .collect(Collectors.toSet());
    result.addAll(initializationSuppressWarningsAnnotations);
    context.getInjector().injectAnnotations(result);
    // update log
    context.log.updateInjectedAnnotations(result);
    // Collect @NullUnmarked annotations on classes for any remaining error.
    Utility.buildTarget(context, false);
    remainingErrors = deserializeErrors(context.targetModuleInfo);
    nullUnMarkedAnnotations =
        remainingErrors.stream()
            .filter(error -> !error.getRegion().isInAnonymousClass())
            .map(
                error ->
                    new AddMarkerAnnotation(
                        context.targetModuleInfo.getLocationOnClass(error.getRegion().clazz),
                        NULL_UNMARKED))
            .collect(Collectors.toSet());
    context.getInjector().injectAnnotations(nullUnMarkedAnnotations);
    // update log
    context.log.updateInjectedAnnotations(nullUnMarkedAnnotations);
  }

  @Override
  public void resolveRemainingErrors() {
    long timer = System.currentTimeMillis();
    Utility.buildTarget(context, false);

    NullAwayCodeFix codeFix =
        config.resolveRemainingErrorMode.isAdvanced()
            ? new AdvancedNullAwayCodeFix(context)
            : config.resolveRemainingErrorMode.isBasic()
                ? new BasicNullAwayCodeFix(context)
                : new AgentBaselineNullAwayCodeFix(context, config.benchmarkPath);

    Set<NullAwayError> remainingErrors = deserializeErrors(context.targetModuleInfo);
    // initialize commit file:
    if (config.actualRunEnabled()) {
      TSVFiles.initialize(
          config.commitHashPath, "ID" + "\t" + NullAwayError.header() + "\t" + "HASH");
    }

    codeFix.collectImpacts();
    AtomicInteger counter = new AtomicInteger(0);
    AtomicInteger processedCounter = new AtomicInteger(0);
    int effectiveTotal;
    if (config.selectedErrorIdsProvided) {
      effectiveTotal = config.selectedErrorIds.size();
    } else if (config.continueRun) {
      effectiveTotal = remainingErrors.size() - config.continueRunAtError + 1;
    } else {
      effectiveTotal = remainingErrors.size();
    }
    // Collect regions with remaining errors.
    logger.trace("Resolving remaining errors: {} errors.", remainingErrors.size());
    // related to log
    AtomicLong previousLineNumber =
        config.actualRunEnabled()
            ? new AtomicLong(Utility.getLineCountOfFile(config.logPath))
            : new AtomicLong(0);
    remainingErrors.stream()
        .collect(Collectors.groupingBy(NullAwayError::getRegion))
        .entrySet()
        .stream()
        .sorted(
            (e1, e2) ->
                e1.getKey().compareTo(e2.getKey())) // Sort regions for deterministic ordering
        .forEach(
            entry -> {
              List<NullAwayError> nullAwayErrors =
                  entry.getValue().stream()
                      .sorted() // Sort errors for deterministic ordering
                      .collect(Collectors.toList());

              nullAwayErrors.forEach(
                  error -> {
                    long timerPerError = System.currentTimeMillis();

                    counter.incrementAndGet();

                    if (config.continueRun && counter.get() < config.continueRunAtError) {
                      logger.trace(
                          "{} : SKIPPING ERROR DUE TO CONTINUE RUN FLAG: {}", counter.get(), error);
                      return;
                    } else if (config.selectedErrorIdsProvided
                        && !config.selectedErrorIds.contains(counter.get())) {
                      logger.trace(
                          "{} : SKIPPING ERROR DUE TO SELECTED ERROR IDS: {}",
                          counter.get(),
                          error);
                      return;
                    }

                    System.out.println(counter.get() + " : TOP LEVEL CALL TO FIX ERROR: " + error);
                    logger.trace("=".repeat(30));
                    logger.trace("CHATGPT.COUNT = {}", ChatGPT.count);
                    logger.trace("CHATGPT.PROMPTS SIZE = {}", ChatGPT.askedPrompts.size());
                    logger.trace("CHATGPT TOKENS USAGE: {}", ChatGPT.tokenUsage);

                    // cleanup
                    ChatGPT.count.set(0);
                    ChatGPT.askedPrompts.clear();
                    ChatGPT.tokenUsage.reset();
                    codeFix.reset();
                    logger.trace("ChatGPT usage reset");
                    if (Main.DEBUG_MODE) {
                      if (error.position.diagnosticLine.contains(Main.DEBUG_LINE)) {
                        System.out.println("At index: " + counter.get());
                      } else {
                        return;
                      }
                    }
                    if (config.resolveRemainingErrorMode.isAgentBaseline()) {
                      cleanBuildOutputFiles(context);
                    }

                    logger.trace("{} : TOP LEVEL CALL TO FIX ERROR: {}", counter.get(), error);
                    Set<RegionRewrite> changes = Set.of();
                    boolean success = true;
                    CommandResult initialBuildResult = Utility.buildTarget(context, true);
                    if (config.resolveRemainingErrorMode.isAgentBaseline()) {
                      logInitialBuildOutputToFile(initialBuildResult);
                    }
                    Set<NullAwayError> errorsBefore =
                        Utility.readErrorsFromOutputDirectory(
                            context, context.targetModuleInfo, NullAwayError.class);

                    int before = errorsBefore.size();

                    try {
                      // AgentBaselineNullAwayCodeFix returns an empty set of changes, as the
                      // agent makes modifications to the code directly.
                      changes = codeFix.fix(error, counter.get());
                      System.out.println("Finished processing.");
                    } catch (Exception e) {
                      changes = Set.of();
                      success = false;
                      System.err.println(
                          "Error while fixing-------: " + e.getMessage() + " \n " + e);
                      e.printStackTrace(System.out);
                      logger.trace(
                          "--------Exception occurred in computing fix-------- | {}",
                          counter.get(),
                          e);
                      try (GitUtility git = GitUtility.instance(config)) {
                        git.resetHard();
                      } catch (Exception ex) {
                        System.err.println("Error while resetting: " + ex.getMessage());
                      }
                    }
                    codeFix.apply(changes);

                    // Log time taken, excluding committing the changes and calculating metrics.
                    long elapsedTimePerError = System.currentTimeMillis() - timerPerError;
                    System.out.println("Time taken to fix error: " + elapsedTimePerError + " ms");
                    int processed = processedCounter.incrementAndGet();
                    String progressBar =
                        "=".repeat((int) (processed * 20.0 / effectiveTotal))
                            + " ".repeat(20 - (int) (processed * 20.0 / effectiveTotal));
                    System.out.printf(
                        "%n>>> PROGRESS [%s] %d / %d (%.0f%%) <<<%n%n",
                        progressBar,
                        processed,
                        effectiveTotal,
                        (processed * 100.0) / effectiveTotal);

                    if (config.actualRunEnabled()) {
                      long currentLineNumber = Utility.getLineCountOfFile(config.logPath);
                      String log =
                          Utility.getLinesFromFile(
                              config.logPath, previousLineNumber.get(), currentLineNumber);
                      previousLineNumber.set(currentLineNumber);

                      postProcessFixedError(
                          error, before, elapsedTimePerError, counter, log, success);
                    }
                  });
            });

    if (config.combined) {
      int totalTestFailures = checkTotalTestFailures();

      TSVFiles.initialize(config.combinedTestFailuresPath, "TOTAL_TEST_FAILURES");
      TSVFiles.addRow(String.valueOf(totalTestFailures), config.combinedTestFailuresPath);
    }

    long elapsed = System.currentTimeMillis() - timer;
    if (config.actualRunEnabled()) {
      TSVFiles.initialize(config.timerPath, "TIME_IN_MILLIS");
      TSVFiles.addRow(String.valueOf(elapsed), config.timerPath);
    }
  }

  private void cleanBuildOutputFiles(Context context) {
    // Delete any *.log file in the benchmarkPath directory.
    try (Stream<Path> stream = Files.list(context.config.benchmarkPath)) {
      stream
          .filter(path -> path.getFileName().toString().endsWith(".log"))
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  logger.error("Error while deleting log file {}: {}", path.getFileName(), e);
                }
              });
    } catch (IOException e) {
      logger.error(
          "Error while listing files in benchmarkPath: {}", context.config.benchmarkPath, e);
    }
  }

  private void logInitialBuildOutputToFile(CommandResult initialBuildResult) {
    try {
      Files.writeString(
          config.initialErrorsLogPath,
          initialBuildResult.output,
          Charset.defaultCharset(),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      logger.error("Error while logging initial build output to file: ", e);
    }
  }

  /**
   * Post processing steps: - Write log file. - Caculate run metrics including tests - Commit
   * changes
   */
  private void postProcessFixedError(
      NullAwayError error,
      int before,
      long elapsedTimePerError,
      AtomicInteger counter,
      String log,
      boolean success) {

    if (success) {
      Utility.executeCommand(
          config, String.format("cd %s && ./gradlew spotlessApply", config.benchmarkPath));
    }

    writeLogFile(error, counter, log);

    // Token usage is calculated differently for agent baseline mode.
    if (!config.resolveRemainingErrorMode.isAgentBaseline()) {
      logChatGPTUsage(counter, ChatGPT.tokenUsage, ChatGPT.count.get());
    }

    boolean patchGenerated = false;
    boolean targetErrorResolved = false;

    // These three metrics are mutually exclusive, but they could all three be
    // false even if patchGenerated is true (patch without effect). They can only
    // be true if patchGenerated is true.
    boolean compilationErrorIntroduced = false;
    boolean targetErrorResolvedWithoutNewErrors = false;
    boolean triggeredNewErrors = false;

    boolean failingTests = false;

    System.out.println("Calculating run metrics...");

    int after = Integer.MAX_VALUE;

    try (GitUtility git = GitUtility.instance(config)) {
      if (success && git.hasChangesToCommit()) {
        patchGenerated = true;
      }
    } catch (Exception ex) {
      System.err.println("Error while checking git changes: " + ex.getMessage());
    }

    if (patchGenerated) {

      try {
        context.targetModuleInfo.getModuleConfiguration().stream()
            .map(configuration -> configuration.dir.resolve("errors.xml"))
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });
        // Build target after applying the fix, to check for remaining errors.
        Utility.buildTarget(context, false);

      } catch (Exception e) {
        System.out.println("Patch caused compilation error, setting after to max value.");
        after = Integer.MAX_VALUE;
        compilationErrorIntroduced = true;
      }

      // If the json file was not created, this indicates that compilation failed before running
      // NullAway.
      AtomicBoolean compilationErrorIntroducedHolder =
          new AtomicBoolean(compilationErrorIntroduced);
      context.targetModuleInfo.getModuleConfiguration().stream()
          .map(configuration -> configuration.dir.resolve("errors.xml"))
          .forEach(
              path -> {
                if (!Files.exists(path)) {
                  System.out.println(
                      "Patch caused compilation error, identified by missing errors.xml file.");
                  compilationErrorIntroducedHolder.set(true);
                }
              });
      compilationErrorIntroduced = compilationErrorIntroducedHolder.get();

      if (!compilationErrorIntroduced) {
        Set<NullAwayError> remainingNullAwayErrors =
            Utility.readErrorsFromOutputDirectory(
                context, context.targetModuleInfo, NullAwayError.class);
        after = remainingNullAwayErrors.size();

        // This equality check seems to be robust against moving lines in the
        // error
        targetErrorResolved = remainingNullAwayErrors.stream().noneMatch(e -> e.equals(error));

        if (targetErrorResolved) {
          // target error removed implies already equality of after and before
          // indicates
          // triggering a new error, as expected would be before - 1
          triggeredNewErrors = after >= before;
        } else {
          triggeredNewErrors = after > before;
        }

        if (targetErrorResolved && !triggeredNewErrors) {
          targetErrorResolvedWithoutNewErrors = true;
        }

        // Check if tests fail, only if no compilation error is introduced, else it stays false.
        if (!config.combined) {
          failingTests = checkForTestFailures(error, counter);
        }
      }
    }

    commitChanges(error, counter, before, after, success, patchGenerated);

    logMetricsForCreatedFix(
        config.combined,
        counter.get(),
        patchGenerated,
        compilationErrorIntroduced,
        targetErrorResolved,
        targetErrorResolvedWithoutNewErrors,
        triggeredNewErrors,
        elapsedTimePerError,
        failingTests,
        after);
  }

  private void writeLogFile(NullAwayError error, AtomicInteger counter, String log) {
    System.out.println("Writing log to file...");
    try {
      // Don't write to the target benchmark anymore for now, to prevent polluting
      // the agent.
      // Log is still saved to the logpath.
      // Files.writeString(
      //    Paths.get(
      //        config.benchmarkPath + String.format("/log-%d.log",
      // counter.get())),
      //    String.format("====================\n%s\nLog:\n%s\n", error, log),
      //    Charset.defaultCharset());

      // write to filesystem
      Files.writeString(
          config.logPath.getParent().resolve(String.format("log-%d.log", counter.get())),
          String.format("====================\n%s\nLog:\n%s\n", error, log),
          Charset.defaultCharset());
    } catch (Exception e) {
      logger.trace("Error while writing log to file: ", e);
    }
  }

  private void logChatGPTUsage(
      AtomicInteger counter, ChatGPTTokenUsage tokenUsage, long promptCounts) {
    System.out.println("Logging ChatGPT token usage...");

    String metricsHeader =
        "ID\tPROMPTS_COUNT\tUNCACHED_PROMPTS_TOKENS\tCACHED_PROMPTS_TOKENS\tRESPONSES_TOKENS\tTOTAL_TOKENS\tCOST_IN_DOLLARS\tMODEL_NAME";
    Path tokenUsagePath = config.logPath.getParent().resolve("token-usages.tsv");
    if (Files.notExists(tokenUsagePath)) {
      TSVFiles.initialize(tokenUsagePath, metricsHeader);
    }
    double cost = ChatGPT.calculateGPTCost(tokenUsage, config.modelName);
    String row =
        String.format(
            "%d\t%d\t%d\t%d\t%d\t%d\t%f\t%s",
            counter.get(),
            promptCounts,
            tokenUsage.getUncachedPromptTokens(),
            tokenUsage.getCachedPromptTokens(),
            tokenUsage.getCompletionTokens(),
            tokenUsage.getTotalTokens(),
            cost,
            config.modelName);

    TSVFiles.addRow(row, tokenUsagePath);
  }

  private boolean checkForTestFailures(NullAwayError error, AtomicInteger counter) {
    boolean failingTests = false;

    System.out.println("Running tests...");
    try {

      Utility.CommandResult testResult =
          Utility.executeCommandAndCaptureOutput(
              config, String.format("cd %s && %s", config.benchmarkPath, config.testCommand));
      if (testResult.exitCode != 0) {
        failingTests = true;
      }

      // Save test logs to file
      try {
        Files.writeString(
            config.logPath.getParent().resolve(String.format("test-log-%d.log", counter.get())),
            String.format(
                "====================\n%s\nTest Exit Code: %d\nTest Output:\n%s\n",
                error, testResult.exitCode, testResult.output),
            Charset.defaultCharset());
      } catch (Exception e) {
        logger.trace("Error while writing test log to file: ", e);
      }
    } catch (Exception e) {
      System.err.println("Error while running tests: " + e.getMessage());
    }

    return failingTests;
  }

  private int checkTotalTestFailures() {
    boolean failingTests = false;

    System.out.println("Running tests...");
    Utility.CommandResult testResult;
    try {

      testResult =
          Utility.executeCommandAndCaptureOutput(
              config,
              String.format("cd %s && %s --continue", config.benchmarkPath, config.testCommand));
      if (testResult.exitCode != 0) {
        failingTests = true;
      }

    } catch (Exception e) {
      System.err.println("Error while running tests: " + e.getMessage());
      return 0;
    }

    // Save test log to file
    try {
      Files.writeString(
          config.logPath.getParent().resolve("test-log-combined.log"),
          String.format(
              "====================\nTest Exit Code: %d\nTest Output:\n%s\n",
              testResult.exitCode, testResult.output),
          Charset.defaultCharset());
    } catch (Exception e) {
      logger.trace("Error while writing test log to file: ", e);
    }

    int failingTestsCount = 0;
    if (!failingTests) {
      return failingTestsCount;
    } else {
      // Extract number of failing tests from test output ("x tests completed, y failed, z skipped")
      try {
        String output = testResult.output;
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
          String line = lines[i];
          if ((line.contains("tests completed") || line.contains("test completed"))
              && line.contains("failed")) {
            String[] parts = line.split(",");
            for (String part : parts) {
              part = part.trim();
              if (part.endsWith("failed")) {
                String[] subParts = part.split(" ");
                failingTestsCount = Integer.parseInt(subParts[0]);
                break;
              }
            }
            break;
          }
        }
      } catch (Exception e) {
        System.err.println("Error while extracting failing tests count: " + e.getMessage());
        failingTestsCount = -1;
      }

      return failingTestsCount;
    }
  }

  private void commitChanges(
      NullAwayError error,
      AtomicInteger counter,
      int before,
      int after,
      boolean success,
      boolean patchGenerated) {
    System.out.println("Trying to commit changes...");

    if (config.combined) {

      try (GitUtility git = GitUtility.instance(config)) {
        if (patchGenerated) {
          if (after < before) {
            logger.trace("Patch reduced errors from {} to {}, committing.", before, after);
            System.out.printf("Patch reduced errors from %d to %d, committing.%n", before, after);
            git.stageAllChanges();
            git.commitChanges("fix: " + error);
            String commitHash = git.getLatestCommitHash();
            TSVFiles.addRow(
                counter.get() + "\t" + error.toTSV() + "\t" + commitHash, config.commitHashPath);
          } else {
            logger.trace(
                "Patch did not reduce errors was {}, now is: {}, resetting.", before, after);
            System.out.printf(
                "Patch did not reduce errors was %d, now is: %d, resetting.%n", before, after);
            git.resetHard();
          }
        } else {
          logger.trace("No patch generated, nothing to commit.");
          System.out.println("No patch generated, nothing to commit.");
          git.resetHard();
        }
      } catch (Exception ex) {
        System.err.println("Error while resetting: " + ex.getMessage());
      }

    } else {
      if (success) {
        try (GitUtility git = GitUtility.instance(config)) {
          System.out.println("Commiting changes...");
          git.stageAllChanges();
          git.commitChanges(
              String.format(
                  "fix: %d - %s - %s - %s",
                  counter.get(),
                  error.messageType,
                  error.position.diagnosticLine.trim(),
                  error.message));
          if (config.pushCommits) {
            System.out.println("Pushing changes to git...");
            git.pushChanges();
          }
          String commitHash = git.getLatestCommitHash();
          TSVFiles.addRow(
              counter.get() + "\t" + error.toTSV() + "\t" + commitHash, config.commitHashPath);
          git.revertLastCommit(config.pushCommits);

        } catch (Exception ex) {
          System.err.println("Error while pushing changes: " + ex.getMessage());
        }
      }
    }
  }

  private void logMetricsForCreatedFix(
      boolean combinedMode,
      int id,
      boolean patchGenerated,
      boolean compilationErrorIntroduced,
      boolean targetErrorResolved,
      boolean targetErrorResolvedWithoutNewErrors,
      boolean triggeredNewErrors,
      long elapsedTimePerError,
      boolean failingTests,
      int remainingErrors) {

    String metricsHeader;
    String row;
    if (combinedMode) {

      metricsHeader =
          "ID\tPATCH_GENERATED\tCOMPILATION_ERROR_INTRODUCED\tTARGET_ERROR_RESOLVED\tTARGET_ERROR_RESOLVED_WITHOUT_NEW_ERRORS\tTRIGGERED_NEW_ERRORS\tEXECUTION_TIME_IN_MILLIS\tREMAINING_ERRORS";
      row =
          String.format(
              "%d\t%b\t%b\t%b\t%b\t%b\t%d\t%d",
              id,
              patchGenerated,
              compilationErrorIntroduced,
              targetErrorResolved,
              targetErrorResolvedWithoutNewErrors,
              triggeredNewErrors,
              elapsedTimePerError,
              remainingErrors);
    } else {
      metricsHeader =
          "ID\tPATCH_GENERATED\tCOMPILATION_ERROR_INTRODUCED\tTARGET_ERROR_RESOLVED\tTARGET_ERROR_RESOLVED_WITHOUT_NEW_ERRORS\tTRIGGERED_NEW_ERRORS\tEXECUTION_TIME_IN_MILLIS\tFAILING_TESTS";
      row =
          String.format(
              "%d\t%b\t%b\t%b\t%b\t%b\t%d\t%b",
              id,
              patchGenerated,
              compilationErrorIntroduced,
              targetErrorResolved,
              targetErrorResolvedWithoutNewErrors,
              triggeredNewErrors,
              elapsedTimePerError,
              failingTests);
    }

    if (Files.notExists(config.metricsPath)) {
      TSVFiles.initialize(config.metricsPath, metricsHeader);
    }

    TSVFiles.addRow(row, config.metricsPath);
  }

  @Override
  public void preprocess() {
    // Collect @Initializer annotations. Heuristically, we add @Initializer on methods which writes
    // a @Nonnull value to more than one uninitialized field, and guarantees initialized fields are
    // nonnull at all exit paths.
    // Collect uninitialized fields.
    Set<OnField> uninitializedFields =
        Utility.readErrorsFromOutputDirectory(
                context, context.targetModuleInfo, NullAwayError.class)
            .stream()
            .filter(
                e ->
                    e.messageType.equals("FIELD_NO_INIT") || e.messageType.equals("METHOD_NO_INIT"))
            .flatMap(Error::getResolvingFixesStream)
            .filter(Fix::isOnField)
            .map(Fix::toField)
            .collect(Collectors.toSet());
    FieldInitializationStore fieldInitializationStore =
        context.targetModuleInfo.getFieldInitializationStore();
    // Collect selected initializers methods.
    Set<AddAnnotation> initializers =
        fieldInitializationStore.findInitializers(uninitializedFields).stream()
            .map(onMethod -> new AddMarkerAnnotation(onMethod, config.initializerAnnot))
            .collect(Collectors.toSet());
    // Inject @Initializer annotations.
    context.getInjector().injectAnnotations(initializers);
  }

  /**
   * Creates a {@link NullAwayError} instance using the provided arguments. It also removes
   * annotation change requests that are on an element with explict nonnull annotation.
   *
   * @param errorType Error Type from NullAway.
   * @param errorMessage Error Message from NullAway.
   * @param region Region where the error is reported.
   * @param path Path to the file where the error is reported.
   * @param position Diagnostic position where the error is reported.
   * @param annotations Annotations that should be added source file to resolve the error.
   * @param module Module where this error is reported.
   * @return Creates and returns the corresponding {@link NullAwayError} instance using the provided
   *     information.
   */
  private NullAwayError createError(
      String errorType,
      String errorMessage,
      Region region,
      Path path,
      DiagnosticPosition position,
      JsonObject infos,
      Set<AddAnnotation> annotations,
      ModuleInfo module) {
    // Filter fixes on elements with explicit nonnull annotations.
    ImmutableSet<AddAnnotation> cleanedAnnotations =
        annotations.stream()
            .filter(
                annot ->
                    !module.getNonnullStore().hasExplicitNonnullAnnotation(annot.getLocation()))
            .collect(ImmutableSet.toImmutableSet());
    return new NullAwayError(
        errorType, errorMessage, region, path, position, infos, cleanedAnnotations);
  }

  @Override
  public void verifyCheckerCompatibility() {
    Path pathToSerializationVersion =
        config.globalDir.resolve("0").resolve("serialization_version.txt");
    if (!Files.exists(pathToSerializationVersion)) {
      throw new RuntimeException(
          "This version of Annotator does not support the using NullAway version, please upgrade NullAway to version >= 0.10.10");
    }
    try {
      int version =
          Integer.parseInt(Files.readString(pathToSerializationVersion, Charset.defaultCharset()));
      Preconditions.checkArgument(
          SUPPORTED_VERSIONS.contains(version),
          "This Annotator version only supports NullAway serialization versions "
              + SUPPORTED_VERSIONS
              + ", but found: "
              + version
              + ", Please update Annotator or NullAway accordingly.");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void prepareConfigFilesForBuild(ImmutableSet<ModuleConfiguration> configurations) {
    configurations.forEach(
        module -> {
          FixSerializationConfig.Builder nullAwayConfig =
              new FixSerializationConfig.Builder()
                  .setSuggest(true, true)
                  .setOutputDirectory(module.dir.toString())
                  .setFieldInitInfo(true);
          nullAwayConfig.writeAsXML(module.checkerConfig.toString());
        });
  }
}
