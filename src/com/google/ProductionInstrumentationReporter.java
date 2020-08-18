package com.google;

import static com.google.common.base.Preconditions.checkState;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.io.File;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class ProductionInstrumentationReporter {

  /**
   * A implementation of the Base64VLQ CharIterator used for decoding the mappings encoded in the
   * JSON string.
   */
  private static class StringCharIterator implements Base64VLQ.CharIterator {

    final String content;
    final int length;
    int current = 0;

    StringCharIterator(String content) {
      this.content = content;
      this.length = content.length();
    }

    @Override
    public char next() {
      return content.charAt(current++);
    }

    @Override
    public boolean hasNext() {
      return current < length;
    }
  }

  private enum InstrumentationType {
    FUNCTION,
    BRANCH,
    BRANCH_DEFAULT;

    public static InstrumentationType fromString(String type) {
      switch (type) {
        case "FUNCTION":
          return FUNCTION;
        case "BRANCH":
          return BRANCH;
        case "BRANCH_DEFAULT":
          return BRANCH_DEFAULT;
        default:
          throw new IllegalArgumentException(
              "A valid type was not provided in the instrumentation mapping report: " + type);
      }
    }

    public static List<InstrumentationType> convertFromStringList(List<String> typesAsString) {
      return typesAsString.stream()
          .map(InstrumentationType::fromString)
          .collect(Collectors.toList());
    }
  }

  /**
   * The class contains the data which is provided by the Production Instrumentation pass in the
   * closure compiler. It will read the data from the file name created with the
   * --instrument_mapping_report and is required to follow the format used by Closure Compiler class
   * VariableMap.
   */
  private static class InstrumentationMapping {

    List<String> fileNames;
    List<String> functionNames;
    List<InstrumentationType> types;
    Map<String, int[]> parameterMapping;

    public InstrumentationMapping() {
      fileNames = new ArrayList<>();
      functionNames = new ArrayList<>();
      types = new ArrayList<>();
      parameterMapping = new HashMap<>();
    }


    /**
     * Parses the file found at location mappingFilePath and populates the properties of this class
     */
    public void parse(String mappingFilePath) throws IOException {
      String instrumentationMappingFile = readFile(mappingFilePath);
      List<String> linesOfFile = Arrays.asList(instrumentationMappingFile.split("\n"));

      checkState(linesOfFile.size() >= 3); // Report should contain at least three lines

      String fileNamesLine = linesOfFile.get(0).trim();
      String functionNamesLine = linesOfFile.get(1).trim();
      String typesLine = linesOfFile.get(2).trim();

      checkState(fileNamesLine.startsWith("FileNames:"));
      checkState(functionNamesLine.startsWith("FunctionNames:"));
      checkState(typesLine.startsWith("Types:"));

      String fileNamesAsJsonArray = fileNamesLine.substring(fileNamesLine.indexOf(":") + 1);
      String functionNamesAsJsonArray = functionNamesLine
          .substring(functionNamesLine.indexOf(":") + 1);
      String typesAsJsonArray = typesLine.substring(typesLine.indexOf(":") + 1);

      Type stringListType = new TypeToken<List<String>>() {
      }.getType();

      Gson gson = new Gson();
      this.fileNames = gson.fromJson(fileNamesAsJsonArray, stringListType);
      this.functionNames = gson.fromJson(functionNamesAsJsonArray, stringListType);

      List<String> typesAsStringList = gson.fromJson(typesAsJsonArray, stringListType);
      this.types = InstrumentationType.convertFromStringList(typesAsStringList);

      for (int i = 3; i < linesOfFile.size(); ++i) {
        String lineItem = linesOfFile.get(i);
        String unqiueParam = lineItem.substring(0, lineItem.indexOf(':'));
        String encodedDetails = lineItem.substring(lineItem.indexOf(':') + 1);

        StringCharIterator encodedDetailsAsCharIt = new StringCharIterator(encodedDetails);

        int[] temp = new int[5];
        temp[0] = Base64VLQ.decode(encodedDetailsAsCharIt); // Index in list this.fileNames
        temp[1] = Base64VLQ.decode(encodedDetailsAsCharIt); // Index in list this.functionNames
        temp[2] = Base64VLQ.decode(encodedDetailsAsCharIt); // Index in list this.types
        temp[3] = Base64VLQ.decode(encodedDetailsAsCharIt); // LineNo
        temp[4] = Base64VLQ.decode(encodedDetailsAsCharIt); // ColNo

        parameterMapping.putIfAbsent(unqiueParam, temp);
      }

    }

    public String getFileName(String uniqueParam) {
      return fileNames.get(parameterMapping.get(uniqueParam)[0]);
    }

    public String getFunctionName(String uniqueParam) {
      return functionNames.get(parameterMapping.get(uniqueParam)[1]);
    }

    public InstrumentationType getType(String uniqueParam) {
      return types.get(parameterMapping.get(uniqueParam)[2]);
    }

    public int getLineNo(String uniqueParam) {
      return parameterMapping.get(uniqueParam)[3];
    }

    public int getColNo(String uniqueParam) {
      return parameterMapping.get(uniqueParam)[4];
    }

    /**
     * This function will return a list of unique parameters which match the criteria specified by
     * the predicate. As an example, it can return a list of all unique parameters which match a
     * specific file name.
     */
    private List<String> getAllMatchingValues(Predicate<String> comparisonPreicate) {
      List<String> result = new ArrayList<>();
      for (String key : parameterMapping.keySet()) {
        if (comparisonPreicate.test(key)) {
          result.add(key);
        }
      }
      return result;
    }

  }

  /**
   * The instrumentation will send the report in a JSON format where the JSON is a dictionary of the
   * encoded params to an object of the data collected. Initially this will just be the frequency
   */
  private static class ProfilingData {

    public int frequency;

    public ProfilingData(int frequency) {
      this.frequency = frequency;
    }

  }

  /**
   * This class contains a detailed breakdown of each instrumentation point. For any encoded param,
   * this class will contain its details. The `executed` property will be a percent of total times
   * the instrumentation point was called across all provided instrumentation reports. i.e if
   * this.executed = 100, then every report would have this point present. Similarly, ProfilingData
   * takes the average of all ProfilingData's for this instrumentation point in all reports.
   */
  private static class ProfilingResult {

    public String param;
    public InstrumentationType type;
    public int lineNo;
    public int colNo;
    public float executed;
    public ProfilingData data;

    ProfilingResult(InstrumentationMapping instrumentationMapping, String param) {
      this.param = param;
      this.type = instrumentationMapping.getType(param);
      this.lineNo = instrumentationMapping.getLineNo(param);
      this.colNo = instrumentationMapping.getColNo(param);
    }

  }

  /**
   * A class which groups profiling results by source file and contains a mapping of them to each
   * function which is present in that source file.
   */
  private static class ProfilingResultByFile {

    public String fileName;
    Map<String, List<ProfilingResult>> profilingDataPerFunction;
  }

  /**
   * This function reads a file at the given filePath and converts the contents into a string.
   */
  private static String readFile(String filePath) throws IOException {
    InputStream is = new FileInputStream(filePath);
    BufferedReader buf = new BufferedReader(new InputStreamReader(is));
    String line = buf.readLine();
    StringBuilder sb = new StringBuilder();
    while (line != null) {
      sb.append(line).append("\n");
      line = buf.readLine();
    }
    String fileAsString = sb.toString();
    return fileAsString;

  }

  /**
   * This function takes the instrumentationMapping and reports sent by the instrumented production
   * code and creates a list of ProfilingResultsByFile with the aggregated information. It will
   * average the ProfilingData provided by allInstrumentationReports and also calculate a percentage
   * of how often a parameter is encounter.
   *
   * @param instrumentationMapping    The instrumentationMapping generated by the Production
   *                                  Instrumentation pass
   * @param allInstrumentationReports A list off all reports sent by the instrumented production
   *                                  code
   * @return A list of ProfilingResultsByFile where each element is the profiling result for a
   * different source file.
   */
  private static List<ProfilingResultByFile> createProfilingResult(
      InstrumentationMapping instrumentationMapping,
      List<Map<String, ProfilingData>> allInstrumentationReports) {

    List<ProfilingResultByFile> result = new ArrayList<>();

    // Iterate over all fileNames since that is what we are grouping by.
    for (String fileName : instrumentationMapping.fileNames) {

      // Get all instrumentation parameters where the source file is fileName.
      List<String> instrumentationPointsPerFile = instrumentationMapping.getAllMatchingValues((s) ->
          fileName.equals(instrumentationMapping.getFileName(s))
      );

      Map<String, List<ProfilingResult>> profilingDataPerFunction = new HashMap<>();

      for (String param : instrumentationPointsPerFile) {
        String functionName = instrumentationMapping.getFunctionName(param);

        float executionAverage = 0;
        float averageFrequency = 0; // Have frequency be float to maintain accuracy
        int runningAverageCounter = 0;

        // For each param, iterate over allInstrumentationReports and check if param is present.
        // If it is, we will average the data, otherwise we will add to the average as if it is 0.
        for (Map<String, ProfilingData> instrumentationData : allInstrumentationReports) {
          ProfilingData profilingData = instrumentationData.get(param);
          runningAverageCounter++;
          if (profilingData != null) {
            // 1 for true means this param was executed, 0 otherwise
            executionAverage += ((1 - executionAverage) / runningAverageCounter);
            averageFrequency += ((profilingData.frequency - averageFrequency)
                / runningAverageCounter);
          } else {
            executionAverage += ((0 - executionAverage) / runningAverageCounter);
            averageFrequency += ((0 - averageFrequency) / runningAverageCounter);
          }
        }

        ProfilingResult profilingResult = new ProfilingResult(instrumentationMapping, param);
        // Round the executionAverage to 2 decimal places for simplicity.
        profilingResult.executed = (Math.round(executionAverage * 10000)/(float) 100);
        profilingResult.data = new ProfilingData(Math.round(averageFrequency));

        if (profilingDataPerFunction.containsKey(functionName)) {
          profilingDataPerFunction.get(functionName).add(profilingResult);
        } else {
          profilingDataPerFunction
              .put(functionName, new ArrayList<>(Arrays.asList(profilingResult)));
        }

      }

      ProfilingResultByFile profilingResultByFile = new ProfilingResultByFile();
      profilingResultByFile.fileName = fileName;
      profilingResultByFile.profilingDataPerFunction = profilingDataPerFunction;

      result.add(profilingResultByFile);

    }

    return result;
  }

  /**
   * Reads all files found in folder and converts the contents of each file to a Map<String,
   * ProfilingData> data structure where it is a mapping of the unique param value to the encoded
   * values. The folder contains all the reports sent by the instrumented production code.
   */
  private static List<Map<String, ProfilingData>> getAllExecutionResults(File folder)
      throws IOException {
    List<Map<String, ProfilingData>> result = new ArrayList<>();

    for (final File fileEntry : folder.listFiles()) {
      String executionResult = readFile(fileEntry.getAbsolutePath());
      Type type = new TypeToken<Map<String, ProfilingData>>() {
      }.getType();
      Map<String, ProfilingData> executedInstrumentationData = new Gson()
          .fromJson(executionResult, type);

      result.add(executedInstrumentationData);

    }

    return result;
  }

  /**
   * Creates a file with the given fileName (including extension) with the contents of the file
   * being provided by fileContents.
   */
  private static void createFile(String fileName, String fileContents) throws IOException {

    File fold = new File(fileName);
    fold.delete();
    File myObj = new File(fileName);
    myObj.createNewFile();

    FileWriter myWriter = new FileWriter(fileName);
    myWriter.write(fileContents);
    myWriter.close();
  }

  public static void main(String[] args) throws IOException {

    InstrumentationMapping instrumentationMapping = new InstrumentationMapping();
    instrumentationMapping.parse("instrumentReport.txt");

    File folder = new File("C:\\Users\\Work\\Desktop\\instrumentationReporter\\temp");
    List<Map<String, ProfilingData>> listOfExecutionResults = getAllExecutionResults(folder);

    List<ProfilingResultByFile> profilingResults = createProfilingResult(instrumentationMapping,
        listOfExecutionResults);
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    createFile("finalResult.json", gson.toJson(profilingResults));

  }
}
