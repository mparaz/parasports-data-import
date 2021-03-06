package examples;

/*
 * #%L
 * Wikidata Toolkit Examples
 * %%
 * Copyright (C) 2014 - 2015 Wikidata Toolkit Developers
 * %%
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
 * #L%
 */

import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetbrains.annotations.Nullable;
import org.wikidata.wdtk.datamodel.helpers.Datamodel;
import org.wikidata.wdtk.datamodel.helpers.ItemDocumentBuilder;
import org.wikidata.wdtk.datamodel.helpers.ReferenceBuilder;
import org.wikidata.wdtk.datamodel.helpers.StatementBuilder;
import org.wikidata.wdtk.datamodel.interfaces.*;
import org.wikidata.wdtk.util.WebResourceFetcherImpl;
import org.wikidata.wdtk.wikibaseapi.*;
import org.wikidata.wdtk.wikibaseapi.apierrors.MediaWikiApiErrorException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ParasportsWork {

    private static final String SITE_URI = "https://para-sports.es/entity/";

    private static String customTrim(String s) {
        final String trim1 = s.trim();

        final StringBuilder sb = new StringBuilder();

        for (char c : trim1.toCharArray()) {
            if ((c != '\u200E') && (c != '\u200F')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // TODO These should be configurable and hidden.
    // Easy solution: Java system properties.

    private static final String WIKIBASE_USERNAME = System.getProperty("wikibase.username");
    private static final String WIKIBASE_PASSWORD = System.getProperty("wikibase.password");
    private static final String WIKIBASE_URL = System.getProperty("wikibase.url");

    public static void main(String[] args) throws Exception {
        // Always set your User-Agent to the name of your application:
        WebResourceFetcherImpl
                .setUserAgent("Wikidata Toolkit ParasportsWork");

        // TODO Use a command-line parsing library

        final ApiConnection connection = new ApiConnection(WIKIBASE_URL);

        // Optional login -- required for operations on real wikis:

        connection.login(WIKIBASE_USERNAME, WIKIBASE_PASSWORD);

        final WikibaseDataEditor wbde = new WikibaseDataEditor(connection, SITE_URI);
        final WikibaseDataFetcher wbdf = new WikibaseDataFetcher(connection, SITE_URI);

        processSpreadsheet(connection, wbdf, wbde, args[0], args[1]);
    }

    private static void processSpreadsheet(ApiConnection connection, WikibaseDataFetcher wbdf, WikibaseDataEditor wbde,
                                           String url, String type) throws Exception {
        // Input file here
        try (final InputStream inputStream = new URL(url).openStream()) {

            XSSFWorkbook wb = new XSSFWorkbook(inputStream);

            // Sheet names are no longer used.
            if ("items".equals(type)) {
                // create: items sheets.
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    final String sheetName = wb.getSheetName(i);
                    System.out.println("Sheet: " + sheetName);
                    processItemCreation(connection, wbde, wb.getSheetAt(i));
                }
            } else if ("statements".equals(type)) {
                // statements sheets.

                final List<Map<String, List<Statement>>> maps = new ArrayList<>();
                final String[] sheetNames = new String[wb.getNumberOfSheets()];

                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    final String sheetName = wb.getSheetName(i);
                    sheetNames[i] = sheetName;
                    System.out.println("Sheet loading: " + sheetName);
                    maps.add(loadStatements(connection, wbdf, wbde, wb.getSheetAt(i)));
                }

                // Help get workbook garbage-collected.
                wb = null;

                // TODO Make the number of concurrent threads configurable.
                final ExecutorService executorService = Executors.newFixedThreadPool(4);

                int i = 0;
                for (final Map<String, List<Statement>> map : maps) {
                    System.out.println("Sheet processing: " + sheetNames[i]);
                    i++;

                    writeStatements(executorService, map, true);
                }

                // Allow the program to shut down.
                // Runnables are no longer needed at the end of the loop.
                executorService.shutdown();
            } else {
                System.err.println("type must be items or statements");
            }
        }
    }

    private static int processItemCreation(ApiConnection connection, WikibaseDataEditor wbde, XSSFSheet ws)
            throws Exception {

        int numberOfCellsProcessed = 0;

        final WbSearchEntitiesAction wbSearchEntitiesAction = new WbSearchEntitiesAction(connection, SITE_URI);

        // In item creation sheets, look up values for duplicate checking.

        final ItemIdValue noid = ItemIdValue.NULL; // used when creating new items
        final int rowNum = ws.getLastRowNum() + 1;

        // Skip over heading rows
        for (int i = 1; i < rowNum; i++) {
            System.out.println("Row: " + (i + 1) + ", of: " + rowNum);
            final XSSFRow row = ws.getRow(i);

            // Can fall off the bottom edge.
            if (row == null) {
                break;
            }

            final String item = customTrim(row.getCell(0).toString());

            // Ignore blank items.
            if ("".equals(item)) {
                continue;
            }

            final String language = customTrim(row.getCell(1).toString());

            final XSSFCell labelCell = row.getCell(2);
            final String label;
            if (labelCell == null) {
                label = "";
            } else {
                label = customTrim(labelCell.toString());
            }

            final XSSFCell descriptionCell = row.getCell(3);
            final String description;
            if (descriptionCell == null) {
                description = "";
            } else {
                description = customTrim(descriptionCell.toString());
            }

            // Aliases might be a non-existent cell
            final XSSFCell aliasesCell = row.getCell(4);
            final String aliases;
            if (aliasesCell == null) {
                aliases = "";
            } else {
                aliases = customTrim(aliasesCell.toString());
            }

            // Duplicate check.
            final List<WbSearchEntitiesResult> wbSearchEntitiesResults =
                    wbSearchEntitiesAction.wbSearchEntities(label, language, true, "item",
                            1L, 0L);

            // Remove the check for aliases, such that matching aliases will also block adding.
            // 2017/7/9 found that duplicate items have been added.
            // if (!wbSearchEntitiesResults.isEmpty() && label.equals(wbSearchEntitiesResults.get(0).getLabel())) {
            if (!wbSearchEntitiesResults.isEmpty()) {
                // Duplicate! Skip.
                System.err.println("Error: Already exists: " + language + "," + label);
                continue;
            }

            // item isn't actually used in Wikidata! Only used in the comment.

            final ItemDocumentBuilder builder = ItemDocumentBuilder.forItemId(noid)
                    .withLabel(label, language)
                    .withDescription(description, language);

            for (final String alias : aliases.split(Pattern.quote("|"))) {
                // Because "|ab" is "" and "ab"
                if (!"".equals(alias)) {
                    builder.withAlias(alias, language);
                }
            }

            // No statements.

            final ItemDocument itemDocument = builder.build();

            final ItemDocument newItemDocument;
            try {
                newItemDocument = wbde.createItemDocument(itemDocument,
                        "Create: " + item);
                System.out.println("Item Created: " + newItemDocument.getItemId().getId());
            } catch (MediaWikiApiErrorException e) {
                e.printStackTrace();
            }
        }

        numberOfCellsProcessed += 4;

        return numberOfCellsProcessed;
    }

    private static final Map<String, String> findItemIdMap = new HashMap<>();

    private static String findItemId(WbSearchEntitiesAction wbSearchEntitiesAction, WikibaseDataEditor wbde,
                                     String text, boolean ignoreP) throws MediaWikiApiErrorException, IOException {
        if (text.matches("^Q\\d+")) {
            // Qnnn item
            return text;
        } else if (text.matches("^P\\d+") && !ignoreP) {
            // Pnnn property, but not ignored (for items and statements)
            return text;
        } else {
            //
            final String actualText;

            // Remove double quotes
            if (text.startsWith("\"")) {
                actualText = text.replaceAll("^[\"]+|[\"]+$", "");
            } else {
                actualText = text;
            }

            // Look up in the map first.
            // Separate the containsKey from the get because the API lookup could be null.
            String itemId;
            boolean hasItemId;

            synchronized (findItemIdMap) {
                hasItemId = findItemIdMap.containsKey(actualText);
                if (hasItemId) {
                    itemId = findItemIdMap.get(actualText);
                    // TODO replace with a proper logging framework
//                    System.err.println("Debug: cache hit, text=" + text + ", itemId=" + itemId);
                } else {
                    itemId = null;
                }
            }

            if (!hasItemId) {
                // Ideally the search should ignore diacritics, but that's to be determined.
                // Search for 1 for exact matches only.
                // Search for many for aliases.
                final List<WbSearchEntitiesResult> wbSearchEntitiesResults =
                        wbSearchEntitiesAction.wbSearchEntities(text, "en", true, "item",
                                1L, 0L);

                // Ignore case when comparing, since wbsearchentities also ignores it.
                if (wbSearchEntitiesResults.isEmpty()) {
                    itemId = null;
                } else {
                    // Search all the results for the exact match.
                    for (final WbSearchEntitiesResult result : wbSearchEntitiesResults) {
                        final String customTrim = customTrim(result.getLabel());
                        if (text.equalsIgnoreCase(customTrim)) {
                            itemId = result.getEntityId();

                            // Might need to repair
                            if (!text.equalsIgnoreCase(result.getLabel())) {
                                final String fix = customTrim(result.getLabel());

                                final ItemDocumentBuilder itemDocumentBuilder = ItemDocumentBuilder
                                        .forItemId(Datamodel.makeItemIdValue(result.getEntityId(), SITE_URI));
                                itemDocumentBuilder.withLabel(fix, "en");
                                wbde.editItemDocument(itemDocumentBuilder.build(), false,
                                        "Trimmed string");

                                System.err.println("Debug: trimmer: " + fix);
                            }
                            break;
                        } else {
                            // Log the difference between what was searched for and what was received,
                            // due to aliasing.
                            System.err.println("Error: looking for: " + text + ", got: " + customTrim);
                        }
                    }
                }

//                System.err.println("Debug: cache miss, text=" + text + ", itemId=" + itemId);

                synchronized (findItemIdMap) {
                    findItemIdMap.put(text, itemId);
                }
            }

            return itemId;
        }
    }

    private static final Map<String, String> subtypesForDataType = new HashMap<>();

    static {
        subtypesForDataType.put(DatatypeIdValue.DT_ITEM, "item");
        subtypesForDataType.put(DatatypeIdValue.DT_PROPERTY, "property");
        subtypesForDataType.put(DatatypeIdValue.DT_STRING, "string");
        subtypesForDataType.put(DatatypeIdValue.DT_URL, "url");
        subtypesForDataType.put(DatatypeIdValue.DT_TIME, "point in time");
        subtypesForDataType.put(DatatypeIdValue.DT_GLOBE_COORDINATES, "globe coordinate");
        subtypesForDataType.put(DatatypeIdValue.DT_QUANTITY, "quantity");
    }

    private static final Map<String, String> subtypeAliases = new HashMap<>();

    static {
        subtypeAliases.put("geographic coordinates", "globe coordinate");
    }

    private static Map<String, List<Statement>> loadStatements(ApiConnection connection,
                                                               WikibaseDataFetcher wbdf,
                                                               WikibaseDataEditor wbde,
                                                               XSSFSheet ws)
            throws MediaWikiApiErrorException, IOException {
        // First attempt: Country language statements. This is fixed columns.
        // Later, variable columns.

        final WbSearchEntitiesAction wbSearchEntitiesAction = new WbSearchEntitiesAction(connection, SITE_URI);

        final ItemIdValue noid = ItemIdValue.NULL; // used when creating new items
        final int rowNum = ws.getLastRowNum() + 1;

        // Track the datatype per property from the API.
        final Map<String, String> dataTypeForProperty = new HashMap<>();

        // Collect statements
        final Map<String, List<Statement>> statementsForItem = new HashMap<>();

        // Skip over heading rows
        for (int i = 1; i < rowNum; i++) {
            System.out.println("Reading row: " + (i + 1) + ", of: " + rowNum);
            final XSSFRow row = ws.getRow(i);

            // Need to watch for nulls off the edge of the row.
            // The edge is hit when the row is null.
            if (row == null) {
                break;
            }

            //  Skip the row when the first cell of the row is null.
            final XSSFCell rowCell = row.getCell(0);
            if (rowCell == null) {
                continue;
            }

            final String untrimmedItem = rowCell.toString();
            final String item = customTrim(untrimmedItem);
            System.out.println("Read item: " + item);

            // Ignore blank items
            if ("".equals(item)) {
                continue;
            }

            // Item names will not have P.
            final String statementItemId = findItemId(wbSearchEntitiesAction, wbde, item, true);

            if (statementItemId == null) {
                System.err.println("Error: Item doesn't exist for statement creation: " + item);
                continue;
            }

            StatementBuilder statementBuilder = null;

            // everything, including the statement itself, is:
            // type, subtype, property, entry

            // Start at negative because it will be incremented at the top
            int columnOffset = -4;

            // Track the number of rows.
            int skippedSets = 0;

            while (true) {
                columnOffset += 4;

                // Stop when falling off the right edge.
                final XSSFCell typeCell = row.getCell(1 + columnOffset);
                if (typeCell == null) {
                    skippedSets++;
                    if (skippedSets == 2) {
                        break;
                    } else {
                        // Previously there was a "skipping" log, but it was useless
                        // because it was always skipping to reach the right end.
                        continue;
                    }
                }

                final String type = customTrim(typeCell.toString()).toLowerCase();

                final XSSFCell subtypeCell = row.getCell(2 + columnOffset);
                if (subtypeCell == null) {
                    break;
                }

                String subtype = customTrim(subtypeCell.toString()).toLowerCase();
                String subtypeAlias = subtypeAliases.get(subtype);
                if (subtypeAlias != null) {
                    subtype = subtypeAlias;
                }

                final XSSFCell propertyCell = row.getCell(3 + columnOffset);
                if (propertyCell == null) {
                    // End of row.
                    break;
                }
                final String property = customTrim(propertyCell.toString());

                // Ensure property is of form Pxxx
                if (!property.matches("^P\\d+")) {
                    System.err.println("Error: Property malformed: " + property + ", for item: " + item);
                    // Can still try next column.
                    continue;
                }

                final XSSFCell entryCell = row.getCell(4 + columnOffset);
                if (entryCell == null) {
                    // End of row.
                    break;
                }

                final String entry = customTrim(entryCell.toString());

                // First time we see this subtype, so check the property against the API.
                // Check if we had already retrieved it.
                String datatypeIri = dataTypeForProperty.get(property);

                if (datatypeIri == null) {
                    final EntityDocument entityDocument = wbdf.getEntityDocument(property);
                    if (!(entityDocument instanceof PropertyDocument)) {
                        System.err.println("Error: Not a property: " + property);
                        continue;
                    }
                    datatypeIri = ((PropertyDocument) entityDocument).getDatatype().getIri();

                    dataTypeForProperty.put(property, datatypeIri);
                }

                String expectedSubtype = subtypesForDataType.get(datatypeIri);

                if (!subtype.equals(expectedSubtype)) {
                    System.err.println("Error: expected subtype: " + expectedSubtype
                            + ", for property: " + property + ", got: " + subtype + ", item: " + item);
                    continue;
                }


                if ("statement".equals(type)) {
                    if (statementBuilder != null) {
                        System.err.println("Error: Statement already set up for item: " + item);
                        continue;
                    }

                    // Initialise the StatementBuilder with the property.
                    // Property wants to be up-front and not later in the loop.

                    // Property needs to be checked for valid format.
                    if (!property.matches("^P\\d+")) {
                        System.err.println("Error: Invalid property format: " + property + ", for item: " + item);
                        continue;
                    }

                    final ItemIdValue itemIdValue = Datamodel.makeItemIdValue(statementItemId, SITE_URI);
                    statementBuilder = StatementBuilder.forSubjectAndProperty(itemIdValue,
                            Datamodel.makePropertyIdValue(property, SITE_URI));
                } else {
                    if (statementBuilder == null) {
                        System.err.println("Error: statement not set up but found another type: " + type +
                                ", for item: " + item);
                    }
                }

                Value value = null;

                // Determine the value
                if ("item".equals(subtype)) {
                    // Ignore P for items.
                    // Ignore blanks.
                    if (!"".equalsIgnoreCase(entry)) {
                        final String itemId = findItemId(wbSearchEntitiesAction, wbde, entry, true);
                        if (itemId == null) {
                            System.err.println("Error: Unknown item: " + entry + ", for item: " + item +
                                    ", type: " + type + ", property: " + property);
                            continue;
                        } else {
                            value = Datamodel.makeItemIdValue(itemId, SITE_URI);
                        }
                    }
                } else if ("property".equals(subtype)) {
                    // The entry is the literal property, no checks
                    if (!entry.matches("^P\\d+")) {
                        System.err.println("Error: Invalid property format: " + entry + ", for item: "
                                + item + ", type: " + type + ", property: " + property);
                        continue;
                    } else {
                        value = Datamodel.makePropertyIdValue(entry, SITE_URI);
                    }
                } else if ("string".equals(subtype)) {
                    value = Datamodel.makeStringValue(entry);
                } else if ("quantity".equals(subtype)) {
                    // Don't make use of the range, so +- 0
                    // Remove any =
                    final String entryNumber = entry.replaceAll("=", "");

                    final BigDecimal entryNumberBigDecimal;
                    try {
                        entryNumberBigDecimal = new BigDecimal(entryNumber);
                    }  catch (NumberFormatException e) {
                        System.err.println("Error: Invalid number: " + entryNumber);
                        continue;
                    }

                    // Remove scientific notation which may be read from POI
                    final BigDecimal entryAsBigDecimal = new BigDecimal(entryNumberBigDecimal.toPlainString());
                    value = Datamodel.makeQuantityValue(entryAsBigDecimal, entryAsBigDecimal, entryAsBigDecimal);
                } else if ("point in time".equals(subtype)) {
                    value = makeTimeValue(entry);
                    if (value == null) {
                        System.err.println("Error: Invalid point in time: " + entry + ", for item: " + item +
                                ", type:" + type + ", property: " + property);
                        continue;
                    }
                } else if ("url".equals(subtype)) {
                    // There is no URL type in Wikidata
                    // We can't support multiple URLs so just detect them, for now.
                    if (entry.contains(",")) {
                        System.out.println("Warning: URL contains , : " + entry + ", taking only the first");
                        value = Datamodel.makeStringValue(entry.split(",")[0]);
                    } else {
                        value = Datamodel.makeStringValue(entry);
                    }
                } else if ("globe coordinate".equals(subtype)) {
                    value = makeGlobalCoordinatesValue(entry);
                    if (value == null) {
                        System.err.println("Error: Invalid globe coordinate: " + entry + ", for item: " + item +
                                ", type:" + type);
                        continue;
                    }
                } else {
                    System.err.println("Error: Unknown subtype: " + subtype + ", for item: " + item +
                            ", type: " + type);
                    continue;
                }

                if (value == null) {
                    System.err.println("Error: No value found for item: " + item + ", subtype: " + subtype);
                    continue;
                }

                // Determine what to do with the value.
                // statementBuilder could be null if the initial property had a problem.
                if (statementBuilder != null) {
                    if ("statement".equals(type)) {
                        statementBuilder.withValue(value);
                    } else if ("instance".equals(type)) {
                        statementBuilder.withQualifierValue(Datamodel.makePropertyIdValue(property, SITE_URI),
                                value);
                    } else if ("reference".equals(type)) {
                        final Reference reference = ReferenceBuilder.newInstance()
                                .withPropertyValue(Datamodel.makePropertyIdValue(property, SITE_URI), value).build();
                        statementBuilder.withReference(reference);
                    }
                }
            }

            // Display the statement built.
            if (statementBuilder == null) {
                // Ignore this row if the statement was never built - like, no item.
                continue;
            }

            statementsForItem.computeIfAbsent(item, (item2) -> new ArrayList<>()).add(statementBuilder.build());
        }

        return statementsForItem;
    }

    private static void writeStatements(ExecutorService executorService,
                                        Map<String, List<Statement>> statementsForItem, boolean writeToServer)
            throws IOException, LoginFailedException {

        for (final Map.Entry<String, List<Statement>> entry : statementsForItem.entrySet()) {

            final String item = entry.getKey();

            // Execute on a per-item basis.
            executorService.submit(() -> {

                try {

                    // Make a fresh connection per thread.
                    final ApiConnection connection = new ApiConnection(WIKIBASE_URL);

                    connection.login(WIKIBASE_USERNAME, WIKIBASE_PASSWORD);

                    final WikibaseDataEditor wbde = new WikibaseDataEditor(connection, SITE_URI);

                    System.out.println("Processing item: " + item);

                    // Merge the statements with the same claim but different references.
                    final Map<Claim, List<Statement>> uniqueClaims = new HashMap<>();

                    for (final Statement statement : entry.getValue()) {
                        uniqueClaims.computeIfAbsent(statement.getClaim(), (claim) -> new ArrayList<>()).add(statement);
                    }

                    final Set<Statement> uniqueStatements = new HashSet<>();
                    for (final List<Statement> statements : uniqueClaims.values()) {

                        final Set<Reference> references = new HashSet<>();

                        StatementBuilder statementBuilder = null;

                        for (final Statement statement : statements) {
                            if (statementBuilder == null) {
                                // Copy the Statement into the ReferenceBuilder.
                                // It only needs to be done once because the subsequent statements will have the same,
                                // only the references will be different.
                                final EntityIdValue subject = statement.getClaim().getSubject();
                                final PropertyIdValue propertyId = statement.getClaim().getMainSnak().getPropertyId();
                                statementBuilder = StatementBuilder.forSubjectAndProperty(subject, propertyId);

                                // Because there's no direct way to copy:
                                if (statement.getClaim().getMainSnak() instanceof SomeValueSnak) {
                                    statementBuilder.withSomeValue();
                                } else if (statement.getClaim().getMainSnak() instanceof NoValueSnak) {
                                    statementBuilder.withNoValue();
                                } else {
                                    statementBuilder.withValue(statement.getValue());
                                }

                                statementBuilder.withQualifiers(statement.getClaim().getQualifiers());
                                statementBuilder.withId(statement.getStatementId());
                                statementBuilder.withRank(statement.getRank());
                            }

                            references.addAll(statement.getReferences());
                        }

                        if (statementBuilder != null) {
                            statementBuilder.withReferences(new ArrayList<>(references));
                            uniqueStatements.add(statementBuilder.build());
                        }
                    }

                    for (final Statement statement : uniqueStatements) {
                        // Save the statements built.
                        if (writeToServer) {
                            final ItemDocument newItemDocument;
                            try {
                                final long startTime = System.currentTimeMillis();
                                newItemDocument = wbde.updateStatements((ItemIdValue) statement.getClaim().getSubject(),
                                        Collections.singletonList(statement),
                                        Collections.emptyList(),
                                        "update statement for " + item);
                                final long elapsedTime = System.currentTimeMillis() - startTime;

                                System.out.println("update: time=" + System.currentTimeMillis() + ", item=" + item +
                                        ", id=" + newItemDocument.getItemId().getId()
                                        + ", statement=" + statement + ", elapsed=" + elapsedTime);
                            } catch (MediaWikiApiErrorException e) {
                                e.printStackTrace();
                                System.out.println("FAILED: item=" + item + ", statement=" + statement);

                            }
                        } else {
                            System.out.println("debug: item=" + item + ", statement=" + statement);
                        }
                    }
                } catch (Exception e) {
                    // Can't do anything or bubble up exception.
                    System.err.println("Error: Inside Runnable" + e);
                }
            });
        }
    }

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"};

    private static final String MONTH_REGEX = "(" + String.join("|", MONTHS) + ")";

    // Year alone could have a decimal point if not entered as string, e.g. 1974.0
    private static final String YEAR_REGEX = "([0-9.]+)";

    private static final String DAY_REGEX = "(\\d+)";

    private static final Pattern YEAR_PATTERN = Pattern.compile("^" + YEAR_REGEX + "$");

    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("^" + YEAR_REGEX + " " + MONTH_REGEX + "$");

    private static final Pattern YEAR_MONTH_DAY_PATTERN = Pattern.compile("^" + YEAR_REGEX + " " + MONTH_REGEX + " " +
            DAY_REGEX + "$");

    @Nullable
    private static Value makeTimeValue(String timeString) {
        // Support YYYY|YYYY MM|YYYY MM DD
        // Should be able to generalise this into many time formats with different precision.

        if (YEAR_PATTERN.matcher(timeString).matches()) {
            return Datamodel.makeTimeValue((int) Double.parseDouble(timeString), (byte) 1, (byte) 1, (byte) 0, (byte) 0,
                    (byte) 0, TimeValue.PREC_YEAR, 0, 1, 0,
                    TimeValue.CM_GREGORIAN_PRO);
        } else {
            final Matcher yearMonthMatcher = YEAR_MONTH_PATTERN.matcher(timeString);

            if (yearMonthMatcher.matches()) {

                final int month = Arrays.asList(MONTHS).indexOf(yearMonthMatcher.group(2));

                return Datamodel.makeTimeValue(Integer.parseInt(yearMonthMatcher.group(1)),
                        (byte) (month + 1), (byte) 1, (byte) 0, (byte) 0,
                        (byte) 0, TimeValue.PREC_MONTH, 0, 1, 0,
                        TimeValue.CM_GREGORIAN_PRO);
            } else {
                final Matcher yearMonthDayMatcher = YEAR_MONTH_DAY_PATTERN.matcher(timeString);

                if (yearMonthDayMatcher.matches()) {
                    final int month = Arrays.asList(MONTHS).indexOf(yearMonthDayMatcher.group(2));

                    return Datamodel.makeTimeValue(Integer.parseInt(yearMonthDayMatcher.group(1)),
                            (byte) (month + 1), Byte.parseByte(yearMonthDayMatcher.group(3)),
                            (byte) 0, (byte) 0,
                            (byte) 0, TimeValue.PREC_DAY, 0, 1, 0,
                            TimeValue.CM_GREGORIAN_PRO);
                }
            }
        }

        // No pattern found
        return null;
    }

    // Geographical patterns can eithre support degrees N/degrees W or just the numbers.
    private static final Pattern GCS_PATTERN = Pattern.compile("([\\-0-9.]+)° N, ([\\-0-9.])+° W");
    private static final Pattern GCS_PATTERN2 = Pattern.compile("([\\-0-9.]+)\\s*,?\\s*([\\-0-9.]+)");

    private static Value makeGlobalCoordinatesValue(String text) {
        // Supports decimal degrees. Example: 30.4168° N, 3.7038° W
        // 0.001 - neigborhood, street - https://en.wikipedia.org/wiki/Decimal_degrees

        final Matcher gcsMatcher = GCS_PATTERN.matcher(text);

        // TODO There should be a better pattern for multiple matches of patterns.
        if (gcsMatcher.matches()) {
            final double latitude = Double.parseDouble(gcsMatcher.group(1));
            final double longitude = Double.parseDouble(gcsMatcher.group(2));

            return Datamodel.makeGlobeCoordinatesValue(latitude, longitude, 0.001,
                    GlobeCoordinatesValue.GLOBE_EARTH);
        } else {
            final Matcher gcsMatcher2 = GCS_PATTERN2.matcher(text);
            if (gcsMatcher2.matches()) {
                final double latitude = Double.parseDouble(gcsMatcher2.group(1));
                final double longitude = Double.parseDouble(gcsMatcher2.group(2));

                return Datamodel.makeGlobeCoordinatesValue(latitude, longitude, 0.001,
                        GlobeCoordinatesValue.GLOBE_EARTH);
            }
            return null;
        }
    }
}
