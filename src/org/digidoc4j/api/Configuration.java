package org.digidoc4j.api;

import org.apache.commons.io.IOUtils;
import org.digidoc4j.api.exceptions.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.isNumeric;

/**
 * Possibility to create custom configurations for {@link org.digidoc4j.api.Container} implementation.
 * <p/>
 * You can specify the configuration mode, either {@link Configuration.Mode#TEST} or {@link Configuration.Mode#PROD}
 * configuration.
 * <p/>
 * Default is {@link Configuration.Mode#PROD}.
 * <p/>
 * It is also possible to set the mode using the System property. Setting the property "digidoc4j.mode" to "TEST" forces
 * the default mode to {@link Configuration.Mode#TEST}  mode
 * <p/>
 * The configuration file must be in yaml format.<br>
 * The configuration file must contain one or more OCSP certificates under the heading "OCSPS"
 * similar to following format (values are examples only):<br>
 * <p>
 * <pre>
 * - OCSP:
 *   CA_CN: your certificate authority common name
 *   CA_CERT: jar://your ca_cn.crt
 *   CN: your common name
 *   CERTS:
 *   - jar://certs/Your first OCSP Certifications file.crt
 *   - jar://certs/Your second OCSP Certifications file.crt
 *   URL: http://ocsp.test.test
 * </pre>
 * <p>All entries must exist and be valid. Under CERTS must be at least one entry.</p>
 * <p/>
 * <p>The configuration file may contain the following additional settings:</p>
 * <p/>
 * DIGIDOC_LOG4J_CONFIG: File containing Log4J configuration parameters.
 * Default value: {@value #DEFAULT_LOG4J_CONFIGURATION}<br>
 * SIGN_OCSP_REQUESTS: Should OCSP requests be signed? Allowed values: true, false
 * DIGIDOC_SECURITY_PROVIDER: Security provider. Default value: {@value #DEFAULT_SECURITY_PROVIDER}<br>
 * DIGIDOC_SECURITY_PROVIDER_NAME: Name of the security provider.
 * Default value: {@value #DEFAULT_SECURITY_PROVIDER_NAME}<br>
 * KEY_USAGE_CHECK: Should key usage be checked? Allowed values: true, false<br>
 * DIGIDOC_OCSP_SIGN_CERT_SERIAL: OCSP Signing certificate serial number<br>
 * DATAFILE_HASHCODE_MODE: Is the datafile containing only a hash (not the actual file)? Allowed values: true, false<br>
 * CANONICALIZATION_FACTORY_IMPL: Canonicalization factory implementation.
 * Default value: {@value #DEFAULT_FACTORY_IMPLEMENTATION}<br>
 * DIGIDOC_MAX_DATAFILE_CACHED: Maximum datafile size that will be cached in MB. Must be numeric.
 * Default value: {@value #DEFAULT_MAX_DATAFILE_CACHED}<br>
 * DIGIDOC_USE_LOCAL_TSL: Use local TSL? Allowed values: true, false<br>
 * DIGIDOC_NOTARY_IMPL: Notary implementation.
 * Default value: {@value #DEFAULT_NOTARY_IMPLEMENTATION}<br>
 * DIGIDOC_TSLFAC_IMPL: TSL Factory implementation.
 * Default value: {@value #DEFAULT_TSL_FACTORY_IMPLEMENTATION}<br>
 * DIGIDOC_FACTORY_IMPL: Factory implementation.
 * Default value: {@value #DEFAULT_FACTORY_IMPLEMENTATION}<br>
 */
public class Configuration {
  final Logger logger = LoggerFactory.getLogger(Configuration.class);

  //  protected static final String DEFAULT_OCSP_SIGN_CERT_SERIAL = "64197687259873867111983257309208039790"; no default
  // Now use setOCSPSigningCertificateSerialNumber(serialNumber). Default serial number = ""
  protected static final String DEFAULT_MAX_DATAFILE_CACHED = "4096";
  protected static final String DEFAULT_CANONICALIZATION_FACTORY_IMPLEMENTATION
      = "ee.sk.digidoc.c14n.TinyXMLCanonicalizer";
  protected static final String DEFAULT_SECURITY_PROVIDER = "org.bouncycastle.jce.provider.BouncyCastleProvider";
  protected static final String DEFAULT_SECURITY_PROVIDER_NAME = "BC";
  protected static final String DEFAULT_LOG4J_CONFIGURATION = "./log4j.properties";
  protected static final String DEFAULT_NOTARY_IMPLEMENTATION = "ee.sk.digidoc.factory.BouncyCastleNotaryFactory";
  protected static final String DEFAULT_TSL_FACTORY_IMPLEMENTATION = "ee.sk.digidoc.tsl.DigiDocTrustServiceFactory";
  protected static final String DEFAULT_FACTORY_IMPLEMENTATION = "ee.sk.digidoc.factory.SAXDigiDocFactory";

  private final Mode mode;
  private static final int JAR_FILE_NAME_BEGIN_INDEX = 6;
  private LinkedHashMap configurationFromFile;
  private String configurationFileName;
  private Hashtable<String, String> jDigiDocConfiguration = new Hashtable<String, String>();
  private ArrayList<String> fileParseErrors;

  public boolean isOCSPSigningConfigurationAvailable() {
    return isNotEmpty(getOCSPAccessCertificateFileName()) || getOCSPAccessCertificatePassword().length != 0;
  }

  public String getOCSPAccessCertificateFileName() {
    logger.debug("Loading OCSPAccessCertificateFile");
    String ocspAccessCertificateFile = getConfigurationParameter("OCSPAccessCertificateFile");
    logger.debug("OCSPAccessCertificateFile " + ocspAccessCertificateFile + " loaded");
    return ocspAccessCertificateFile;
  }

  public char[] getOCSPAccessCertificatePassword() {
    logger.debug("Loading OCSPAccessCertificatePassword");
    char[] result ={};
    String password = getConfigurationParameter("OCSPAccessCertificatePassword");
    if(isNotEmpty(password)) {
      result = password.toCharArray();
    }
    logger.debug("OCSPAccessCertificatePassword loaded");
    return result;
  }

  public void setOCSPAccessCertificateFileName(String fileName) {
    logger.debug("Setting OCSPAccessCertificateFileName: " + fileName);
    setConfigurationParameter("OCSPAccessCertificateFile", fileName);
    logger.debug("OCSPAccessCertificateFile is set");
  }

  public void setOCSPAccessCertificatePassword(char[] password) {
    logger.debug("Setting OCSPAccessCertificatePassword: ");
    setConfigurationParameter("OCSPAccessCertificatePassword", String.valueOf(password));
    logger.debug("OCSPAccessCertificatePassword is set");
  }

  /**
   * Application mode
   */
  public enum Mode {
    TEST,
    PROD
  }

  /**
   * Operating system
   */
  protected enum OS {
    Linux,
    Win,
    OSX
  }

  Map<Mode, Map<String, String>> configuration = new HashMap<Mode, Map<String, String>>();

  /**
   * Create new configuration
   */
  public Configuration() {
    logger.debug("");
    if ("TEST".equalsIgnoreCase(System.getProperty("digidoc4j.mode")))
      mode = Mode.TEST;
    else
      mode = Mode.PROD;

    logger.info("Configuration loaded for " + mode + " mode");

    initDefaultValues();
  }

  /**
   * Create new configuration for application mode specified
   *
   * @param mode Application mode
   */
  public Configuration(Mode mode) {
    logger.debug("Mode: " + mode);
    this.mode = mode;
    initDefaultValues();
  }

  private void initDefaultValues() {
    logger.debug("");
    Map<String, String> testConfiguration = new HashMap<String, String>();
    Map<String, String> prodConfiguration = new HashMap<String, String>();

//  testConfiguration.put("tslLocation", "http://ftp.id.eesti.ee/pub/id/tsl/trusted-test-mp.xml");
    testConfiguration.put("tslLocation", "file:conf/trusted-test-tsl.xml");
//    prodConfiguration.put("tslLocation", "file:conf/tl-map.xml");
    prodConfiguration.put("tslLocation", "http://ftp.id.eesti.ee/pub/id/tsl/trusted-test-mp.xml");
//    prodConfiguration.put("tslLocation", "http://sr.riik.ee/tsl/estonian-tsl.xml");

    testConfiguration.put("tspSource", "http://tsa01.quovadisglobal.com/TSS/HttpTspServer");
    prodConfiguration.put("tspSource", "http://tsa01.quovadisglobal.com/TSS/HttpTspServer");

    testConfiguration.put("validationPolicy", "conf/constraint.xml");
    prodConfiguration.put("validationPolicy", "conf/constraint.xml");

    testConfiguration.put("pkcs11ModuleLinux", "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so");
    prodConfiguration.put("pkcs11ModuleLinux", "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so");

    testConfiguration.put("ocspSource", "http://www.openxades.org/cgi-bin/ocsp.cgi");
    prodConfiguration.put("ocspSource", "http://ocsp.sk.ee/");

    configuration.put(Mode.TEST, testConfiguration);
    configuration.put(Mode.PROD, prodConfiguration);

    logger.debug("Test configuration:\n" + configuration.get(Mode.TEST));
    logger.debug("Prod configuration:\n" + configuration.get(Mode.PROD));

    loadInitialConfigurationValues();
  }

  /**
   * Add configuration settings from a file
   *
   * @param file File name
   * @return configuration hashtable
   */
  public Hashtable<String, String> loadConfiguration(String file) {
    logger.debug("File " + file);
    configurationFromFile = new LinkedHashMap();
    Yaml yaml = new Yaml();
    configurationFileName = file;
    InputStream resourceAsStream = getResourceAsStream(file);
    if (resourceAsStream == null) {
      try {
        resourceAsStream = new FileInputStream(file);
      } catch (FileNotFoundException e) {
        throw new ConfigurationException(e);
      }
    }
    try {
      configurationFromFile = (LinkedHashMap) yaml.load(resourceAsStream);
    } catch (Exception e) {
      ConfigurationException exception = new ConfigurationException("Configuration file " + file + " "
          + "is not a correctly formatted yaml file");
      logger.error(exception.getMessage());
      throw exception;
    }
    return mapToJDigiDocConfiguration();
  }

  /**
   * Get CA Certificates
   *
   * @return list of X509 Certificates
   */
  public List<X509Certificate> getCACerts() {
    logger.debug("");
    List<X509Certificate> certificates = new ArrayList<X509Certificate>();
    ArrayList<String> certificateAuthorityCerts =
        getCACertsAsArray((LinkedHashMap) configurationFromFile.get("DIGIDOC_CA"));
    for (String certFile : certificateAuthorityCerts) {
      try {
        certificates.add(getX509CertificateFromFile(certFile));
      } catch (CertificateException e) {
        logger.warn("Not able to read certificate from file " + certFile + ". " + e.getMessage());
      }
    }
    return certificates;
  }

  X509Certificate getX509CertificateFromFile(String certFile) throws CertificateException {
    logger.debug("File: " + certFile);
    CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

    InputStream certAsStream = getResourceAsStream(certFile.substring(JAR_FILE_NAME_BEGIN_INDEX));
    X509Certificate cert = (X509Certificate) certificateFactory.generateCertificate(certAsStream);
    IOUtils.closeQuietly(certAsStream);

    return cert;
  }

  private InputStream getResourceAsStream(String certFile) {
    logger.debug("");
    return getClass().getClassLoader().getResourceAsStream(certFile);
  }

  /**
   * Gives back all configuration parameters needed for jDigiDoc
   *
   * @return Hashtable containing jDigiDoc configuration parameters
   */

  private Hashtable<String, String> mapToJDigiDocConfiguration() {
    logger.debug("loading JDigiDoc configuration");

    fileParseErrors = new ArrayList<String>();

    loadInitialConfigurationValues();

    loadCertificateAuthorityCerts();
    loadOCSPCertificates();

    reportFileParseErrors();

    return jDigiDocConfiguration;
  }

  private void reportFileParseErrors() {
    if (fileParseErrors.size() > 0) {
      StringBuilder errorMessage = new StringBuilder();
      errorMessage.append("Configuration file ");
      errorMessage.append(configurationFileName);
      errorMessage.append(" contains error(s):\n");
      for (String message : fileParseErrors) {
        errorMessage.append(message);
      }
      throw new ConfigurationException(errorMessage.toString());
    }
  }

  private void loadInitialConfigurationValues() {
    logger.debug("");
    setJDigiDocConfigurationValue("DIGIDOC_LOG4J_CONFIG", DEFAULT_LOG4J_CONFIGURATION);
    setJDigiDocConfigurationValue("SIGN_OCSP_REQUESTS", Boolean.toString(mode == Mode.PROD));
    setJDigiDocConfigurationValue("DIGIDOC_SECURITY_PROVIDER", DEFAULT_SECURITY_PROVIDER);
    setJDigiDocConfigurationValue("DIGIDOC_SECURITY_PROVIDER_NAME", DEFAULT_SECURITY_PROVIDER_NAME);
    setJDigiDocConfigurationValue("KEY_USAGE_CHECK", "false");
    setJDigiDocConfigurationValue("DIGIDOC_OCSP_SIGN_CERT_SERIAL", "");
    setJDigiDocConfigurationValue("DATAFILE_HASHCODE_MODE", "false");
    setJDigiDocConfigurationValue("CANONICALIZATION_FACTORY_IMPL", DEFAULT_CANONICALIZATION_FACTORY_IMPLEMENTATION);
    setJDigiDocConfigurationValue("DIGIDOC_MAX_DATAFILE_CACHED", DEFAULT_MAX_DATAFILE_CACHED);
    setJDigiDocConfigurationValue("DIGIDOC_USE_LOCAL_TSL", "true");
    setJDigiDocConfigurationValue("DIGIDOC_NOTARY_IMPL", DEFAULT_NOTARY_IMPLEMENTATION);
    setJDigiDocConfigurationValue("DIGIDOC_TSLFAC_IMPL", DEFAULT_TSL_FACTORY_IMPLEMENTATION);
    setJDigiDocConfigurationValue("DIGIDOC_OCSP_RESPONDER_URL", getOcspSource());
    setJDigiDocConfigurationValue("DIGIDOC_FACTORY_IMPL", DEFAULT_FACTORY_IMPLEMENTATION);
  }

  /**
   * get factory implementation method
   *
   * @return implementation method
   */
  public String getFactoryImplementation() {
    String factoryImplementation = jDigiDocConfiguration.get("DIGIDOC_FACTORY_IMPL");
    logger.debug("Factory implementation: " + factoryImplementation);
    return factoryImplementation;
  }

  /**
   * get the TSL Factory implementation method
   *
   * @return implementation method
   */
  public String getTslFactoryImplementation() {
    String tslFactoryImplementation = jDigiDocConfiguration.get("DIGIDOC_TSLFAC_IMPL");
    logger.debug("TSL factory implementation: " + tslFactoryImplementation);
    return tslFactoryImplementation;
  }

  /**
   * get notary implementation method
   *
   * @return notary implementation method
   */
  public String getNotaryImplementation() {
    String notaryImplementation = jDigiDocConfiguration.get("DIGIDOC_NOTARY_IMPL");
    logger.debug("Notary implementation: " + notaryImplementation);
    return notaryImplementation;
  }

  /**
   * set if local TSL should be used
   *
   * @param useLocalTSL uses local TSL if set to true.
   */
  public void setUseLocalTsl(boolean useLocalTSL) {
    logger.debug("Use local TSL: " + useLocalTSL);
    jDigiDocConfiguration.put("DIGIDOC_USE_LOCAL_TSL", Boolean.toString(useLocalTSL));
  }


  /**
   * get if local TSL should be used
   *
   * @return True if using local TSL, false if not
   */
  public Boolean usesLocalTsl() {
    boolean usesLocalTsl = Boolean.parseBoolean(jDigiDocConfiguration.get("DIGIDOC_USE_LOCAL_TSL"));
    logger.debug("Uses local TSL: " + usesLocalTsl);
    return usesLocalTsl;
  }

  /**
   * get canonicalization factory implementation
   *
   * @return implementation
   */
  public String getCanonicalizationFactoryImplementation() {
    String canonicalizationFactoryImplementation = jDigiDocConfiguration.get("CANONICALIZATION_FACTORY_IMPL");
    logger.debug("Canonicalization factory implementation: " + canonicalizationFactoryImplementation);
    return canonicalizationFactoryImplementation;
  }

  /**
   * Indicates if Data file should be in Hashcode mode
   *
   * @return boolean
   */
  public boolean isDataFileInHashCodeMode() {
    boolean isDataFileInHasCodeMode = Boolean.parseBoolean(jDigiDocConfiguration.get("DATAFILE_HASHCODE_MODE"));
    logger.debug("Is datafile in Hashcode mode: " + isDataFileInHasCodeMode);
    return isDataFileInHasCodeMode;
  }

  /**
   * Set Datafile hashcode mode
   *
   * @param hashCodeMode set hashcode mode
   */
  public void setDataFileHashCodeMode(Boolean hashCodeMode) {
    logger.debug("Hashcode mode: " + hashCodeMode);
    jDigiDocConfiguration.put("DATAFILE_HASHCODE_MODE", hashCodeMode.toString());
  }

  private void setJDigiDocConfigurationValue(String key, String defaultValue) {
    logger.debug("Key: " + key + ", default value: " + defaultValue);
    jDigiDocConfiguration.put(key, defaultIfNull(key, defaultValue));
  }


  /**
   * Load Log4J configuration parameters from a file
   *
   * @param fileName File name
   */
  public void setLog4JConfiguration(String fileName) {
    logger.debug("Filename: " + fileName);
    jDigiDocConfiguration.put("DIGIDOC_LOG4J_CONFIG", fileName);
  }

  /**
   * Get Log4J parameters
   *
   * @return Log4j parameters
   */
  public String getLog4JConfiguration() {
    String log4jConfiguration = jDigiDocConfiguration.get("DIGIDOC_LOG4J_CONFIG");
    logger.debug("Log4J configuration: " + log4jConfiguration);
    return log4jConfiguration;
  }


  /**
   * Set OCSP Signing Certificate Serial Number
   *
   * @param serialNumber Serial number
   */
  public void setOCSPSigningCertificateSerialNumber(String serialNumber) {
    logger.debug("Set OCSP Signing certificate serialnumber: " + serialNumber);
    jDigiDocConfiguration.put("DIGIDOC_OCSP_SIGN_CERT_SERIAL", serialNumber);
  }

  /**
   * Get OCSP Signing Certificate Serial number
   *
   * @return Serial number
   */
  public String getOCSPSigningCertificateSerialNumber() {
    String serialNumber = jDigiDocConfiguration.get("DIGIDOC_OCSP_SIGN_CERT_SERIAL");
    logger.debug("OCSP signing certificate serial number: " + serialNumber);
    return serialNumber;
  }


  /**
   * Set the maximum size of data files to be cached
   *
   * @param maxDataFileCached Maximum size in MB
   */
  public void setMaxDataFileCached(long maxDataFileCached) {
    logger.debug("Set maximum datafile cached to: " + maxDataFileCached);
    jDigiDocConfiguration.put("DIGIDOC_MAX_DATAFILE_CACHED", Long.toString(maxDataFileCached));
  }

  /**
   * Returns configuration item must be OCSP request signed. Reads it from configuration parameter SIGN_OCSP_REQUESTS.
   * Default value is true for {@link Configuration.Mode#PROD} and false for {@link Configuration.Mode#TEST}
   *
   * @return must be OCSP request signed
   */
  public boolean hasToBeOCSPRequestSigned() {
    return Boolean.parseBoolean(jDigiDocConfiguration.get("SIGN_OCSP_REQUESTS"));
  }

  /**
   * Get the maximum size of data files to be cached
   *
   * @return Size
   */
  public long getMaxDataFileCached() {
    String maxDataFileCached = jDigiDocConfiguration.get("DIGIDOC_MAX_DATAFILE_CACHED");
    logger.debug("Maximum datafile cached: " + maxDataFileCached);
    return Long.parseLong(maxDataFileCached);
  }

  private String defaultIfNull(String configParameter, String defaultValue) {
    logger.debug("Parameter: " + configParameter + ", default value: " + defaultValue);
    if (configurationFromFile == null) return defaultValue;
    Object value = configurationFromFile.get(configParameter);
    if (value != null) {
      return verifyValueIsAllowed(configParameter, value.toString()) ? value.toString() : "";
    }
    String configuredValue = jDigiDocConfiguration.get(configParameter);
    return configuredValue != null ? configuredValue : defaultValue;
  }

  private boolean verifyValueIsAllowed(String configParameter, String value) {
    logger.debug("");
    boolean errorFound = false;
    List<String> mustBeBooleans =
        asList("SIGN_OCSP_REQUESTS", "KEY_USAGE_CHECK", "DATAFILE_HASHCODE_MODE", "DIGIDOC_USE_LOCAL_TSL");
    List<String> mustBeNumerics = asList("DIGIDOC_MAX_DATAFILE_CACHED");

    if (mustBeBooleans.contains(configParameter)) {
      if (!("true".equals(value.toLowerCase()) || "false".equals(value.toLowerCase()))) {
        String errorMessage = "Configuration parameter " + configParameter + " should be set to true or false "
            + "but the actual value is: " + value + ". Configuration file: " + configurationFileName;
        logger.error(errorMessage);
        fileParseErrors.add(errorMessage);
        errorFound = true;
      }
    }

    if (mustBeNumerics.contains(configParameter)) {
      if (!isNumeric(value)) {
        String errorMessage = "Configuration parameter " + configParameter + " should have a numeric value "
            + "but the actual value is: " + value + ". Configuration file: " + configurationFileName;
        logger.error(errorMessage);
        fileParseErrors.add(errorMessage);
        errorFound = true;
      }
    }
    return (!errorFound);
  }

  private void loadOCSPCertificates() {
    String errorMessage;
    logger.debug("");
    LinkedHashMap digiDocCA = (LinkedHashMap) configurationFromFile.get("DIGIDOC_CA");

    @SuppressWarnings("unchecked")
    ArrayList<LinkedHashMap> ocsps = (ArrayList<LinkedHashMap>) digiDocCA.get("OCSPS");
    if (ocsps == null) {
      errorMessage = "No OCSPS entry found or OCSPS entry is empty. Configuration file: " + configurationFileName;
      logger.error(errorMessage);
      fileParseErrors.add(errorMessage);
      return;
    }

    int numberOfOCSPCertificates = ocsps.size();
    jDigiDocConfiguration.put("DIGIDOC_CA_1_OCSPS", String.valueOf(numberOfOCSPCertificates));

    for (int i = 1; i <= numberOfOCSPCertificates; i++) {
      String prefix = "DIGIDOC_CA_1_OCSP" + i;
      LinkedHashMap ocsp = ocsps.get(i - 1);

      List<String> entries = asList("CA_CN", "CA_CERT", "CN", "URL");
      for (String entry : entries) {
        if (!loadOCSPCertificateEntry(entry, ocsp, prefix)) {
          errorMessage = "OCSPS list entry " + i + " does not have an entry for " + entry
              + " or the entry is empty\n";
          logger.error(errorMessage);
          fileParseErrors.add(errorMessage);
        }
      }

      if (!getOCSPCertificates(prefix, ocsp)) {
        errorMessage = "OCSPS list entry " + i + " does not have an entry for CERTS or the entry is empty\n";
        logger.error(errorMessage);
        fileParseErrors.add(errorMessage);
      }
    }
  }

  private boolean loadOCSPCertificateEntry(String ocspsEntryName, LinkedHashMap ocsp, String prefix) {

    Object ocspEntry = ocsp.get(ocspsEntryName);
    if (ocspEntry == null) return false;
    jDigiDocConfiguration.put(prefix + "_" + ocspsEntryName, ocspEntry.toString());
    return true;
  }


  @SuppressWarnings("unchecked")
  private boolean getOCSPCertificates(String prefix, LinkedHashMap ocsp) {
    logger.debug("");
    ArrayList<String> certificates = (ArrayList<String>) ocsp.get("CERTS");
    if (certificates == null) return false;
    for (int j = 0; j < certificates.size(); j++) {
      if (j == 0) {
        jDigiDocConfiguration.put(prefix + "_CERT", certificates.get(0));
      } else {
        jDigiDocConfiguration.put(prefix + "_CERT_" + j, certificates.get(j));
      }
    }
    return true;
  }

  private void loadCertificateAuthorityCerts() {
    logger.debug("");
    LinkedHashMap digiDocCA = (LinkedHashMap) configurationFromFile.get("DIGIDOC_CA");
    ArrayList<String> certificateAuthorityCerts = getCACertsAsArray(digiDocCA);

    jDigiDocConfiguration.put("DIGIDOC_CAS", "1");
    jDigiDocConfiguration.put("DIGIDOC_CA_1_NAME", digiDocCA.get("NAME").toString());
    jDigiDocConfiguration.put("DIGIDOC_CA_1_TRADENAME", digiDocCA.get("TRADENAME").toString());
    int numberOfCACertificates = certificateAuthorityCerts.size();
    jDigiDocConfiguration.put("DIGIDOC_CA_1_CERTS", String.valueOf(numberOfCACertificates));

    for (int i = 0; i < numberOfCACertificates; i++) {
      String certFile = certificateAuthorityCerts.get(i);
      jDigiDocConfiguration.put("DIGIDOC_CA_1_CERT" + (i + 1), certFile);
    }
  }

  @SuppressWarnings("unchecked")
  private ArrayList<String> getCACertsAsArray(LinkedHashMap jDigiDocCa) {
    logger.debug("");
    return (ArrayList<String>) jDigiDocCa.get("CERTS");
  }

  /**
   * get the TSL location
   *
   * @return TSL location
   */
  public String getTslLocation() {
    logger.debug("");
    String tslLocation = getConfigurationParameter("tslLocation");
    logger.debug("TSL Location: " + tslLocation);
    return tslLocation;
  }

  /**
   * Set the TSL location
   *
   * @param tslLocation TSL Location to be used
   */
  public void setTslLocation(String tslLocation) {
    logger.debug("Set TSL location: " + tslLocation);
    setConfigurationParameter("tslLocation", tslLocation);
  }

  /**
   * Get the TSP Source
   *
   * @return TSP Source
   */
  public String getTspSource() {
    logger.debug("");
    String tspSource = getConfigurationParameter("tspSource");
    logger.debug("TSP Source: " + tspSource);
    return tspSource;
  }

  /**
   * Set the TSP Source
   *
   * @param tspSource TSPSource to be used
   */
  public void setTspSource(String tspSource) {
    logger.debug("Set TSP source: " + tspSource);
    setConfigurationParameter("tspSource", tspSource);
  }

  /**
   * Get the OCSP Source
   *
   * @return OCSP Source
   */
  public String getOcspSource() {
    logger.debug("");
    String ocspSource = getConfigurationParameter("ocspSource");
    logger.debug("OCSP source: " + ocspSource);
    return ocspSource;
  }

  /**
   * Set the OCSP source
   *
   * @param ocspSource OCSP Source to be used
   */
  public void setOcspSource(String ocspSource) {
    logger.debug("Set OCSP source: " + ocspSource);
    setConfigurationParameter("ocspSource", ocspSource);
  }

  /**
   * Get the validation policy
   *
   * @return Validation policy
   */
  public String getValidationPolicy() {
    logger.debug("");
    String validationPolicy = getConfigurationParameter("validationPolicy");
    logger.debug("Validation policy: " + validationPolicy);
    return validationPolicy;
  }

  /**
   * Set the validation policy
   *
   * @param validationPolicy Policy to be used
   */
  public void setValidationPolicy(String validationPolicy) {
    logger.debug("Set validation policy: " + validationPolicy);
    setConfigurationParameter("validationPolicy", validationPolicy);
  }

  String getPKCS11ModulePathForOS(OS os, String key) {
    logger.debug("");
    return getConfigurationParameter(key + os);
  }

  /**
   * Get the PKCS11 Module path
   *
   * @return path
   */
  public String getPKCS11ModulePath() {
    logger.debug("");
    String path = getPKCS11ModulePathForOS(OS.Linux, "pkcs11Module");
    logger.debug("PKCS11 module path: " + path);
    return path;
  }

  private void setConfigurationParameter(String key, String value) {
    logger.debug("Key: " + key + ", value: " + value);
    configuration.get(mode).put(key, value);
  }

  private String getConfigurationParameter(String key) {
    logger.debug("Key: " + key);
    String value = configuration.get(mode).get(key);
    logger.debug("Value: " + value);
    return value;
  }
}
