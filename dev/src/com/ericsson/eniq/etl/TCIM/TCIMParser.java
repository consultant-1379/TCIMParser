package com.ericsson.eniq.etl.TCIM;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import com.distocraft.dc5000.etl.parser.Main;
import com.distocraft.dc5000.etl.parser.MeasurementFile;
import com.distocraft.dc5000.etl.parser.Parser;
import com.distocraft.dc5000.etl.parser.SourceFile;
import com.ericsson.eniq.common.ENIQEntityResolver;

public class TCIMParser extends DefaultHandler implements Parser {

	private static final String JVM_TIMEZONE = (new SimpleDateFormat("Z")).format(new Date());
	SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	private Logger log;
	private String techPack;
	private String setType;
	private String setName;
	private int status = 0;
	private Main mainParserObject = null;
	private String workerName = "";
	private SourceFile sourceFile;

	private String charValue;

	private String id;
	private String moClass;
	private boolean collect;
	private MeasurementFile mFile = null;
	private String dataType;
	private int depth;
	private LinkedList<String> moList = null;

	private HashMap<String, String> tempDataMap = null;

	// Interface parameters
	private String filenameIdentifier;
	private String jvmTimezoneIdentifier;
	private String dirnameIdentifier;
	private String aomIdentifier;
	private String dateTimeIdentifier;
	private String ossIdIdentifier;
	private String hostNameIdentifier;
	private String moidIdentifier;
	private String timelevel;
	private String periodDuration;

	private String datetimeIdPattern;
	private String aomPattern;
	private String hostnamePattern;
	private String aom;
	private String hostname;
	private String datetimeId;
	private String moId;

	private String moIdSeparator;
	private boolean removeVsData;

	private String duplicateCounters;
	private ArrayList<String> duplicateCounterList = null;
	private LinkedHashMap<String, LinkedList<String>> duplicateCounterValueMap = new LinkedHashMap<String, LinkedList<String>>();

	private int sequenceIndex;

	// Parameters for throughput measurement.
	private long parseStartTime;
	private long totalParseTime;
	private long fileSize;
	private int fileCount;

	@Override
	public void run() {
		try {

			this.status = 2;
			SourceFile sf = null;
			parseStartTime = System.currentTimeMillis();
			while ((sf = mainParserObject.nextSourceFile()) != null) {

				try {
					fileCount++;
					fileSize += sf.fileSize();
					mainParserObject.preParse(sf);
					parse(sf, techPack, setType, setName);
					mainParserObject.postParse(sf);
				} catch (Exception e) {
					mainParserObject.errorParse(e, sf);
				} finally {
					mainParserObject.finallyParse(sf);
				}
			}
			totalParseTime = System.currentTimeMillis() - parseStartTime;
			if (totalParseTime != 0) {
				log.info("Parsing Performance :: " + fileCount + " files parsed in " + totalParseTime
						+ " milliseconds, filesize is " + fileSize + " bytes and throughput : "
						+ (fileSize / totalParseTime) + " bytes/ms.");
			}
		} catch (Exception e) {
			log.log(Level.WARNING, "Worker parser failed with exception: ", e);
		} finally {

			this.status = 3;
		}
	}

	@Override
	public void init(final Main main, final String techPack, final String setType, final String setName,
			final String workerName) {
		this.mainParserObject = main;
		this.techPack = techPack;
		this.setType = setType;
		this.setName = setName;
		this.status = 1;
		this.workerName = workerName;

		String logWorkerName = "";
		if (workerName.length() > 0) {
			logWorkerName = "." + workerName;
		}

		log = Logger
				.getLogger("etl." + techPack + "." + setType + "." + setName + ".parser.TCIMParser" + logWorkerName);
	}

	@Override
	public void parse(final SourceFile sf, final String techPack, final String setType, final String setName)
			throws Exception {

		final long start = System.currentTimeMillis();
		long middle = 0;

		id = "";
		moClass = "";
		collect = false;
		mFile = null;
		dataType = "";
		depth = 0;
		moList = new LinkedList<String>();

		this.sourceFile = sf;

		getInterfaceProperties();

		log.log(Level.FINEST, "Creating SAXParser instance...");
		try {
			SAXParserFactory spf = SAXParserFactory.newInstance();
			spf.setNamespaceAware(true);
			SAXParser parser = spf.newSAXParser();
			final XMLReader xmlReader = parser.getXMLReader();
			xmlReader.setContentHandler(this);
			xmlReader.setErrorHandler(this);

			xmlReader.setEntityResolver(new ENIQEntityResolver(log.getName()));
			middle = System.currentTimeMillis();
			xmlReader.parse(new InputSource(sourceFile.getFileInputStream()));
		} catch (SAXException e) {
			log.log(Level.SEVERE, "Unable to parse the XML file, ", e);
		} catch (IOException e) {
			log.log(Level.SEVERE, "Unable to read the XML file, ", e);
		} catch (ParserConfigurationException e) {
			log.log(Level.SEVERE, "ParserConfigurationException thrown, ", e);
		}

		final long end = System.currentTimeMillis();
		log.log(Level.FINER, "Data parsed. Parser initialization took " + (middle - start) + " ms, parsing took "
				+ (end - middle) + " ms. Total: " + (end - start) + " ms.");
	}

	public void getInterfaceProperties() {

		dirnameIdentifier = sourceFile.getProperty("dirnameIdentifier", "DIRNAME");
		filenameIdentifier = sourceFile.getProperty("filenameIdentifier", "FILENAME");
		jvmTimezoneIdentifier = sourceFile.getProperty("jvmTimezoneIdentifier", "JVM_TIMEZONE");
		aomIdentifier = sourceFile.getProperty("aomIdentifier", "AOM");
		dateTimeIdentifier = sourceFile.getProperty("dateTimeIdentifier", "DATETIME_ID");
		ossIdIdentifier = sourceFile.getProperty("ossIdIdentifier", "OSS_ID");
		hostNameIdentifier = sourceFile.getProperty("hostNameIdentifier", "HOSTNAME");
		moidIdentifier = sourceFile.getProperty("moidIdentifier", "MOID");
		
		timelevel = sourceFile.getProperty("timelevel", "24H");
		periodDuration = sourceFile.getProperty("periodDuration", "1440");

		moIdSeparator = sourceFile.getProperty("moIdSeparator", ",");
		removeVsData = sourceFile.getProperty("removeVsData", "true").equalsIgnoreCase("true") ? true : false;

		aomPattern = sourceFile.getProperty("aomPattern", null);
		if (aomPattern != null) {
			aom = transformFileVariables("aomPattern", sourceFile.getName(), aomPattern);
		} else {
			aom = "";
		}

		hostnamePattern = sourceFile.getProperty("hostnamePattern", null);
		if (hostnamePattern != null) {
			hostname = transformFileVariables("hostnamePattern", sourceFile.getName(), hostnamePattern);
		} else {
			hostname = "";
		}

		datetimeIdPattern = sourceFile.getProperty("datetimeIdPattern", null);
		if (datetimeIdPattern != null) {
			datetimeId = transformFileVariables("datetimeIdPattern", sourceFile.getName(), datetimeIdPattern);
		} else {
			datetimeId = "";
		}

		duplicateCounters = sourceFile.getProperty("duplicateCounters", "").trim();
		if (!duplicateCounters.isEmpty()) {
			duplicateCounterList = new ArrayList<String>();
			for (String counter : duplicateCounters.split(","))
				duplicateCounterList.add(counter.toLowerCase());
		}
	}

	private String transformFileVariables(String transformation, String filename, String pattern) {
		String result = null;
		try {
			Pattern p = Pattern.compile(pattern);
			Matcher m = p.matcher(filename);
			if (m.matches()) {
				result = m.group(1);
			}
		} catch (PatternSyntaxException e) {
			log.log(Level.SEVERE, "Error performing transformFileVariables for TCIMParser: " + transformation, e);
		}
		return result;
	}

	@Override
	public int status() {
		return status;
	}

	private void addMandatoryKeys() {
		mFile.addData(filenameIdentifier, sourceFile.getName());
		mFile.addData(jvmTimezoneIdentifier, JVM_TIMEZONE);
		mFile.addData(dirnameIdentifier, sourceFile.getDir());
		mFile.addData(aomIdentifier, aom);
		mFile.addData(dateTimeIdentifier, datetimeId);
		mFile.addData(hostNameIdentifier, hostname);
		mFile.addData("TIMELEVEL", timelevel);
		mFile.addData("PERIOD_DURATION", periodDuration);

		// mFile.addData(ossIdIdentifier, ossId);
	}

	/**
	 * Event handlers
	 */
	public void startDocument() {
		log.log(Level.FINEST, "Entering into the startDocument method.");
	}

	public void endDocument() throws SAXException {

		log.log(Level.FINEST, "Entering into the endDocument method.");
		log.log(Level.FINEST, "Value of depth at the end of the document is " + depth);

		if (mFile != null) {
			try {
				log.log(Level.FINER, "Parser has completed parsing the file content.");
				mFile.close();
			} catch (Exception e) {
				log.log(Level.WARNING, "Error closing measurement file with exception: ", e);
			}
		}

	}

	public void startElement(String namespaceURI, String localName, String qName, Attributes attrs)
			throws SAXException {

		charValue = "";

		if ("xn:VsDataContainer".equals(qName)) {
			depth++;
			id = attrs.getValue("id");
		} else if (qName.endsWith(":attributes")) {
			sequenceIndex = 1;
			collect = true;
			tempDataMap = new HashMap<String, String>();
			duplicateCounterValueMap = new LinkedHashMap<String, LinkedList<String>>();
			moClass = "";
		}
	}

	public void endElement(String namespaceURI, String localName, String qName) throws SAXException {

		if (qName.equals("xn:attributes")) {
			moId = "";
			collect = false;

			if (!moClass.isEmpty()) {

				// Populating moId
				for (String mo : moList) {
					if (moId.equals("")) {
						moId = mo.trim();
					} else {
						moId = moId + moIdSeparator + mo.trim();
					}
				}

				if (!duplicateCounterValueMap.isEmpty()) {

					// Number of Counters with multiple Values
					int n = duplicateCounterValueMap.size();

					// To keep track of next element in each of the n Counter Value Lists
					int[] indices = new int[n];
					for (int i = 0; i < n; i++) {
						indices[i] = 0;
					}

					while (true) {

						try {
							mFile = Main.createMeasurementFile(sourceFile, moClass, techPack, setType, setName,
									this.workerName, log);
						} catch (Exception e) {
							log.log(Level.WARNING, "Error occured while creating measurement file with TagID: "
									+ moClass + ". Exception: ", e);
							mFile = null;
						}

						if (mFile != null) {
							mFile.addData(tempDataMap);

							addMandatoryKeys();

							mFile.addData(moidIdentifier, moId);

							mFile.addData("SEQUENCE_INDEX", Integer.toString(sequenceIndex));
						}

						// Each run of this For loop provides one possible combination of all the
						// counters.
						for (int i = 0; i < n; i++) {
							String counterName = (String) duplicateCounterValueMap.keySet().toArray()[i];

							if (!duplicateCounterValueMap.get(counterName).isEmpty() && mFile != null) {
								mFile.addData(counterName, duplicateCounterValueMap.get(counterName).get(indices[i]));
							}
						}

						if (mFile != null) {
							try {
								log.log(Level.FINEST,
										"Saving the data from the measurement file with Tag ID: " + moClass);
								mFile.saveData();
							} catch (Exception e) {
								log.log(Level.WARNING, "Error occured while trying to save the data for " + moClass, e);
							}

							try {
								mFile.close();
							} catch (Exception e) {
								log.log(Level.WARNING,
										"Error occured while trying to close the measurement file for " + moClass, e);
							}
						}

						sequenceIndex++;

						int next = n - 1;
						while (next >= 0 && (indices[next] + 1 >= duplicateCounterValueMap
								.get(duplicateCounterValueMap.keySet().toArray()[next]).size())) {
							next--;
						}

						if (next < 0) {
							break;
						}

						indices[next]++;

						// For all arrays to the right of this array current index again points to first
						// element
						for (int i = next + 1; i < n; i++) {
							indices[i] = 0;
						}
					}

					// Clean up
					tempDataMap.clear();

					for (String counterName : duplicateCounterValueMap.keySet()) {
						duplicateCounterValueMap.get(counterName).clear();
					}
					duplicateCounterValueMap.clear();

				} else {
					try {
						mFile = Main.createMeasurementFile(sourceFile, moClass, techPack, setType, setName,
								this.workerName, log);
					} catch (Exception e) {
						log.log(Level.WARNING, "Error occured while creating measurement file with TagID: " + moClass
								+ ". Exception: ", e);
						mFile = null;
					}

					if (mFile != null) {
						mFile.addData(tempDataMap);

						addMandatoryKeys();

						mFile.addData(moidIdentifier, moId);

						mFile.addData("SEQUENCE_INDEX", Integer.toString(sequenceIndex));

						try {
							log.log(Level.FINEST, "Saving the data from the measurement file with Tag ID: " + moClass);
							mFile.saveData();
						} catch (Exception e) {
							log.log(Level.WARNING, "Error occured while trying to save the data for " + moClass, e);
						}

						try {
							mFile.close();
						} catch (Exception e) {
							log.log(Level.WARNING,
									"Error occured while trying to close the measurement file for " + moClass, e);
						}

						// Clean up
						tempDataMap.clear();
					}
				}
			} else {
				log.log(Level.FINE,
						"No xn:VsDataType tag is encountered inside the xn:attributes tag. Hence, no Measurement File is created.");
			}
		}

		if (collect == true) {
			if (qName.equals("xn:vsDataType")) {
				dataType = charValue.toString();
				moClass = (removeVsData == true) ? charValue.replaceAll("vsData", "") : charValue;
				moList.add(moClass + "=" + id);
			} else {

				String key = qName.split(":")[1];
				String value = charValue.trim();
				
				if (value.equalsIgnoreCase("NIL") || value.equalsIgnoreCase("NULL")) {
					value = null;
				}

				if (duplicateCounterList != null && duplicateCounterList.contains(key.toLowerCase())) {
					if (duplicateCounterValueMap.containsKey(key)) {
						duplicateCounterValueMap.get(key).add(value);
					} else {
						LinkedList<String> counterValues = new LinkedList<String>();
						counterValues.add(value);

						duplicateCounterValueMap.put(key, counterValues);
					}
				} else {
					tempDataMap.put(key, value);
				}
			}
		}

		if (qName.equals("xn:VsDataContainer")) {
			moList.removeLast();
			depth--;
		}
	}

	public void characters(char[] ch, int start, int length) throws SAXException {
		for (int i = start; i < start + length; i++) {
			// If no control char
			if (ch[i] != '\\' && ch[i] != '\n' && ch[i] != '\r' && ch[i] != '\t') {
				charValue += ch[i];
			}
		}
	}

}
