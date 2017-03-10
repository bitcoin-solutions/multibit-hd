package org.multibit.hd.core.services;

import com.fasterxml.jackson.dataformat.yaml.snakeyaml.error.YAMLException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.utils.Threading;
import org.multibit.commons.concurrent.SafeExecutors;
import org.multibit.hd.brit.core.seed_phrase.Bip39SeedPhraseGenerator;
import org.multibit.hd.brit.core.seed_phrase.SeedPhraseGenerator;
import org.multibit.hd.core.config.BitcoinConfiguration;
import org.multibit.hd.core.config.Configuration;
import org.multibit.hd.core.config.Configurations;
import org.multibit.hd.core.config.Yaml;
import org.multibit.hd.core.dto.WalletId;
import org.multibit.hd.core.dto.WalletMode;
import org.multibit.hd.core.dto.WalletPassword;
import org.multibit.hd.core.dto.WalletSummary;
import org.multibit.hd.core.error_reporting.ExceptionHandler;
import org.multibit.hd.core.events.ShutdownEvent;
import org.multibit.hd.core.exceptions.PaymentsLoadException;
import org.multibit.hd.core.logging.LoggingFactory;
import org.multibit.hd.core.managers.InstallationManager;
import org.multibit.hd.core.managers.WalletManager;
import org.multibit.hd.core.utils.BitcoinNetwork;
import org.multibit.hd.hardware.core.HardwareWalletClient;
import org.multibit.hd.hardware.core.HardwareWalletService;
import org.multibit.hd.hardware.core.wallets.HardwareWallets;
import org.multibit.hd.hardware.keepkey.clients.KeepKeyHardwareWalletClient;
import org.multibit.hd.hardware.keepkey.wallets.v1.KeepKeyV1HidHardwareWallet;
import org.multibit.hd.hardware.trezor.clients.TrezorHardwareWalletClient;
import org.multibit.hd.hardware.trezor.wallets.v1.TrezorV1HidHardwareWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * <p>Factory to provide the following to application API:</p>
 * <ul>
 * <li>Entry point to configured instances of Core services</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class CoreServices {

  private static final Logger log = LoggerFactory.getLogger(CoreServices.class);

  private static final int TREZOR_WALLET_SERVICE_INDEX = 0;
  private static final int KEEP_KEY_WALLET_SERVICE_INDEX = 1;

  /**
   * Keep track of selected application events (e.g. exchange rate changes, environment alerts etc)
   * Not an optional service
   */
  private static ApplicationEventService applicationEventService;

  /**
   * Keep track of environment events (e.g. debugger, file permissions etc) across all wallets
   * Not an optional service
   */
  private static EnvironmentCheckingService environmentCheckingService;

  /**
   * Keep track of shutdown events and ensure the configuration is persisted
   * Not an optional service
   */
  private static ConfigurationService configurationService;

  /**
   * Allow lightweight processing of external data as part of Payment Protocol support
   * Not an optional service
   */
  private static PaymentProtocolService paymentProtocolService;

  /**
   * Keep track of the Bitcoin network for the current wallet
   * Optional service until wallet is unlocked
   */
  private static Optional<BitcoinNetworkService> bitcoinNetworkService = Optional.absent();

  /**
   * Keep track of the various hardware wallet services for the application
   * Empty if system does not support hardware wallets (or none attached)
   * Device listed in position 0 is the active one for the current session
   */
  private static List<Optional<HardwareWalletService>> hardwareWalletServices = Lists.newArrayList();

  /**
   * Keep track of the current hardware wallet service
   */
  private static Optional<HardwareWalletService> currentHardwareWalletService = Optional.absent();

  /**
   * Keeps track of the contact service for the current wallet
   * Optional service until wallet is unlocked
   */
  private static Optional<PersistentContactService> contactService = Optional.absent();

  /**
   * Keeps track of the wallet service for the current wallet
   * Optional service until wallet is unlocked
   */
  private static Optional<WalletService> walletService = Optional.absent();

  /**
   * Keeps track of the backup service for the current wallet
   * Optional service until wallet is unlocked
   */
  private static Optional<BackupService> backupService = Optional.absent();

  /**
   * Manages CoreService startup and shutdown operations
   */
  private static volatile ListeningExecutorService coreServices = null;
  private static Context context;

  public static Context getContext() {
    return context;
  }

  /**
   * Utilities have a private constructor
   */
  private CoreServices() {
  }

  /**
   * Start the bare minimum to allow correct operation
   */
  public static void bootstrap() {

    configurationService = new ConfigurationService();

    // Start the configuration service to ensure shutdown events are trapped
    configurationService.start();

    log.debug("Loading configuration...");
    Optional<Configuration> configuration;
    try (InputStream is = new FileInputStream(InstallationManager.getConfigurationFile())) {
      // Load configuration (providing a default if none exists)
      configuration = Yaml.readYaml(is, Configuration.class);
    } catch (YAMLException | IOException e) {
      configuration = Optional.absent();
    }

    if (configuration.isPresent()) {
      log.info("Using current configuration");
      Configurations.currentConfiguration = configuration.get();
    } else {
      log.warn("Using default configuration");
      Configurations.currentConfiguration = Configurations.newDefaultConfiguration();
    }

    // Set up the bitcoinj context
    context = new Context(NetworkParameters.fromID(NetworkParameters.ID_MAINNET));
    log.debug("Context identity: {}", System.identityHashCode(context));

    // Ensure any errors can be reported
    ExceptionHandler.registerExceptionHandler();
  }

  /**
   * <p>Initialises the core services, and can act as an independent starting point for headless operations</p>
   *
   * @param args Any command line arguments
   */
  public static void main(String[] args) {

    // Order is important here
    applicationEventService = new ApplicationEventService();
    environmentCheckingService = new EnvironmentCheckingService();

    if (configurationService == null) {
      bootstrap();
    }

    // Configure logging now that we have a configuration
    new LoggingFactory(Configurations.currentConfiguration.getLogging(), "MultiBit HD").configure();

    // Start environment checking service
    environmentCheckingService.start();

    // Start application event service
    applicationEventService.start();

    // Create Payment Protocol service (once configuration identifies the network parameters)
    paymentProtocolService = new PaymentProtocolService(BitcoinNetwork.current().get());
    paymentProtocolService.start();

    // Configure Bitcoinj
    Threading.UserThread.WARNING_THRESHOLD = Integer.MAX_VALUE;
  }

  /**
   * <p>Typically called directly after a ShutdownEvent is broadcast.</p>
   * <p>Depending on the shutdown type this method will trigger a <code>System.exit(0)</code> to ensure graceful termination.</p></p>
   *
   * @param shutdownType The
   */
  @SuppressFBWarnings({"DM_GC"})
  public static synchronized void shutdownNow(final ShutdownEvent.ShutdownType shutdownType) {

    switch (shutdownType) {
      default:
      case HARD:
        log.info("Applying hard shutdown.");

        shutdownWalletSupportServices(shutdownType);
        shutdownApplicationSupportServices(shutdownType);

        log.info("Issuing system exit");
        System.exit(0);
        break;
      case SOFT:
        log.info("Applying soft shutdown.");

        shutdownWalletSupportServices(shutdownType);
        shutdownApplicationSupportServices(shutdownType);

        // Suggest a garbage collection to free resources under test
        System.gc();
        break;
      case SWITCH:
        log.info("Applying wallet switch.");

        // Attempt to locate the first ready wallet since we're switching
        // away from the current one
        CoreServices.useFirstReadyHardwareWalletService();

        shutdownWalletSupportServices(shutdownType);

        // Suggest a garbage collection
        System.gc();
        break;
    }

  }

  /**
   * <p>Shutdown all application support services (non-optional)</p>
   * <ul>
   * <li>Contact service</li>
   * <li>Bitcoin network service</li>
   * <li>Wallet service</li>
   * <li>Backup service</li>
   * </ul>
   *
   * @param shutdownType The shutdown type providing context
   */
  private static void shutdownWalletSupportServices(ShutdownEvent.ShutdownType shutdownType) {

    // Allow graceful shutdown of managed services in the correct order
    shutdownService(contactService, shutdownType);

    // Close the Bitcoin network service (peer group, save wallet etc)
    shutdownService(bitcoinNetworkService, shutdownType);
    shutdownService(walletService, shutdownType);
    shutdownService(backupService, shutdownType);

    // Clear the references
    bitcoinNetworkService = Optional.absent();
    contactService = Optional.absent();
    walletService = Optional.absent();
    backupService = Optional.absent();

  }

  /**
   * <p>Shutdown all application support services (non-optional)</p>
   * <ul>
   * <li>Environment checking service</li>
   * <li>Application event service</li>
   * <li>Payment protocol service</li>
   * </ul>
   *
   * @param shutdownType The shutdown type providing context
   */
  private static void shutdownApplicationSupportServices(ShutdownEvent.ShutdownType shutdownType) {

    // Shutdown non-managed services
    stopHardwareWalletServices();

    // Allow graceful shutdown in the correct order
    if (configurationService != null) {
      configurationService.shutdownNow(shutdownType);
    }
    if (environmentCheckingService != null) {
      environmentCheckingService.shutdownNow(shutdownType);
    }
    if (applicationEventService != null) {
      applicationEventService.shutdownNow(shutdownType);
    }
    if (paymentProtocolService != null) {
      paymentProtocolService.shutdownNow(shutdownType);
    }

    // Be judicious when clearing references since it leads to complex behaviour during shutdown

  }

  /**
   * <p>Shutdown a managed service</p>
   *
   * @param service      The service
   * @param shutdownType The shutdown type providing context
   */
  private static void shutdownService(Optional<? extends ManagedService> service, ShutdownEvent.ShutdownType shutdownType) {
    if (service.isPresent()) {
      service.get().shutdownNow(shutdownType);
    }
  }

  /**
   * @param bitcoinConfiguration The Bitcoin configuration providing exchange and currency details
   *
   * @return A new exchange service based on the current configuration
   */
  public static ExchangeTickerService createAndStartExchangeService(BitcoinConfiguration bitcoinConfiguration) {

    // Breaks the "get or create" pattern because it is used to examine all exchanges

    log.debug("Create and start new exchange ticker service");
    final ExchangeTickerService exchangeTickerService = new ExchangeTickerService(bitcoinConfiguration);
    exchangeTickerService.start();

    return exchangeTickerService;

  }

  /**
   * <p>Gets or creates but does not start the hardware wallet services</p>
   *
   * @return Create a list of optional hardware wallet services targeting different devices (Trezor, KeepKey etc)
   */
  public static synchronized List<Optional<HardwareWalletService>> getOrCreateHardwareWalletServices() {

    if (hardwareWalletServices.isEmpty() && Configurations.currentConfiguration.isTrezor()) {

      log.debug("Attempting to create hardware wallet service entries");

      // Attempt Trezor support
      hardwareWalletServices.add(TREZOR_WALLET_SERVICE_INDEX, createTrezorHardwareWalletService());

      // Attempt KeepKey support
      hardwareWalletServices.add(KEEP_KEY_WALLET_SERVICE_INDEX, createKeepKeyHardwareWalletService());

    }

    // Ensure that we have absent entries if the list is still empty
    if (hardwareWalletServices.isEmpty()) {
      log.debug("No hardware wallet service enabled");
      hardwareWalletServices.add(TREZOR_WALLET_SERVICE_INDEX, Optional.<HardwareWalletService>absent());
      hardwareWalletServices.add(KEEP_KEY_WALLET_SERVICE_INDEX, Optional.<HardwareWalletService>absent());
    }

    return hardwareWalletServices;

  }

  /**
   * @param walletMode The wallet mode
   *
   * @return The hardware wallet for the given mode (may not be present or active) - consider getCurrentHardwareWalletService instead
   */
  public static synchronized Optional<HardwareWalletService> getHardwareWalletService(WalletMode walletMode) {

    if (walletMode == null) {
      return Optional.absent();
    }

    log.debug("Get hardware wallet service for {}", walletMode.name());

    switch (walletMode) {
      case TREZOR:
        return hardwareWalletServices.get(TREZOR_WALLET_SERVICE_INDEX);
      case KEEP_KEY:
        return hardwareWalletServices.get(KEEP_KEY_WALLET_SERVICE_INDEX);
      default:
        return Optional.absent();
    }

  }

  /**
   * <p>The selection rules are as follows:</p>
   * <ol>
   *   <li>If a current hardware wallet service is in place then use that (to avoid accidentally switching wallets during a session)</li>
   *   <li>If no current hardware wallet is present, then check Trezor then KeepKey and so on</li>
   *   <li>The first hardware wallet service in the "isDeviceReady" state is set as the current hardware wallet service</li>
   * </ol>
   *
   * <p>Use the shutdown and restart hardware wallet methods to force a reset</p>
   *
   * @return The current hardware wallet service if present
   */
  public static Optional<HardwareWalletService> useFirstReadyHardwareWalletService() {

    log.debug("Searching for first ready hardware wallet...");

    // Always use the current if it is present and ready
    if (currentHardwareWalletService.isPresent() && currentHardwareWalletService.get().isDeviceReady()) {
      log.debug("Using current hardware wallet service");
      return currentHardwareWalletService;
    }

    // Prevent incorrect initialisation (perhaps during testing) from causing problems
    if (hardwareWalletServices.isEmpty()) {
      log.debug("No hardware wallet services configured");
      return Optional.absent();
    }

    final Optional<HardwareWalletService> result;
    if (hardwareWalletServices.get(TREZOR_WALLET_SERVICE_INDEX).isPresent()
      && hardwareWalletServices.get(TREZOR_WALLET_SERVICE_INDEX).get().isDeviceReady()) {
      log.debug("Trezor is ready");
      result = hardwareWalletServices.get(TREZOR_WALLET_SERVICE_INDEX);
    } else if (hardwareWalletServices.get(KEEP_KEY_WALLET_SERVICE_INDEX).isPresent()
      && hardwareWalletServices.get(KEEP_KEY_WALLET_SERVICE_INDEX).get().isDeviceReady()) {
      log.debug("KeepKey is ready");
      result = hardwareWalletServices.get(KEEP_KEY_WALLET_SERVICE_INDEX);
    } else {
      log.debug("No device is ready");
      result = Optional.absent();
    }

    // Ensure the current hardware wallet service tracks the first ready if requested
    currentHardwareWalletService = result;

    return result;
  }

  /**
   * @return The current hardware wallet service (set by first ready)
   */
  public static Optional<HardwareWalletService> getCurrentHardwareWalletService() {
    return currentHardwareWalletService;
  }

  /**
   * Simplify FEST testing for hardware wallets
   *
   * @param hardwareWalletServices The hardware wallet services to use (simulates detection on the wire)
   */
  public static void setHardwareWalletServices(List<Optional<HardwareWalletService>> hardwareWalletServices) {
    Preconditions.checkState(InstallationManager.unrestricted, "The hardware wallet services should only be set in the context of testing");
    Preconditions.checkNotNull(hardwareWalletServices, "The hardware wallet services must be present");
    CoreServices.hardwareWalletServices = hardwareWalletServices;
  }

  /**
   * <p>Stop the hardware wallet services</p>
   */
  public static void stopHardwareWalletServices() {

    log.debug("Stop hardware wallet service (expect all subscribers to be purged)");

    for (Optional<HardwareWalletService> hardwareWalletService : hardwareWalletServices) {
      if (hardwareWalletService.isPresent()) {
        hardwareWalletService.get().stopAndWait();
      }
    }

    // Reset the list to empty to allow re-population later
    hardwareWalletServices.clear();

    // Clear the current hardware wallet service
    currentHardwareWalletService = Optional.absent();

  }

  /**
   * @return Create a new backup service or return the extant one
   */
  public static BackupService getOrCreateBackupService() {

    log.debug("Getting backup service");
    if (!backupService.isPresent()) {
      backupService = Optional.of(new BackupService());
    }

    return backupService.get();
  }

  /**
   * @return Create a new seed phrase generator
   */
  public static SeedPhraseGenerator newSeedPhraseGenerator() {

    log.debug("Creating new BIP39 seed phrase generator");
    return new Bip39SeedPhraseGenerator();
  }

  /**
   * @return The application event service singleton
   */
  public static ApplicationEventService getApplicationEventService() {
    return applicationEventService;

  }

  /**
   * @return The environment checking service singleton
   */
  public static EnvironmentCheckingService getEnvironmentCheckingService() {
    log.debug("Get environment checking service");
    return environmentCheckingService;
  }

  /**
   * @return The configuration service singleton
   */
  public static ConfigurationService getConfigurationService() {
    log.debug("Get configuration service");
    return configurationService;
  }

  /**
   * @return The started payment protocol service
   */
  public static PaymentProtocolService getPaymentProtocolService() {
    log.debug("Get or create payment protocol service");
    return paymentProtocolService;

  }

  /**
   * @return The Bitcoin network service - note that this is NOT started
   */
  public static synchronized BitcoinNetworkService getOrCreateBitcoinNetworkService() {
    log.trace("Get Bitcoin network service ");

    // Require session singleton so only a null will create a new instance
    if (!bitcoinNetworkService.isPresent()) {
      bitcoinNetworkService = Optional.of(new BitcoinNetworkService(BitcoinNetwork.current().get()));
    }

    return bitcoinNetworkService.get();

  }

  /**
   * <p>Stop the Bitcoin network service and allow garbage collection</p>
   *
   * <p>This occurs on the CoreServices task thread</p>
   */
  public static synchronized void stopBitcoinNetworkService() {
    if (coreServices == null) {
      coreServices = SafeExecutors.newFixedThreadPool(10, "core-services");
    }

    log.debug("Stop Bitcoin network service");
    coreServices.submit(
      new Runnable() {
        @Override
        public void run() {
          if (bitcoinNetworkService.isPresent()) {
            bitcoinNetworkService.get().shutdownNow(ShutdownEvent.ShutdownType.HARD);
            bitcoinNetworkService = Optional.absent();
          }
        }
      });
  }

  /**
   * @return The wallet service for the current wallet
   */
  public static Optional<WalletService> getCurrentWalletService() {

    return walletService;

  }

  /**
   * @param walletId The wallet ID for the wallet
   *
   * @return The started wallet service for the given wallet ID
   */
  public static WalletService getOrCreateWalletService(WalletId walletId) {

    log.trace("Get or create wallet service");

    Preconditions.checkNotNull(walletId, "'walletId' must be present");

    // Check if the wallet service has been created for this wallet ID
    if (!walletService.isPresent()) {
      File applicationDirectory = InstallationManager.getOrCreateApplicationDataDirectory();

      walletService = Optional.of(new WalletService(BitcoinNetwork.current().get()));
      try {
        if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
          CharSequence password = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWalletPassword().getPassword();
          walletService.get().initialise(applicationDirectory, walletId, password);
        }
      } catch (PaymentsLoadException ple) {
        ExceptionHandler.handleThrowable(ple);
      }
      walletService.get().start();
    }

    // Return the wallet service
    return walletService.get();

  }

  /**
   * @return The contact service for the current wallet
   */
  public static ContactService getCurrentContactService() {

    log.debug("Get current contact service");

    Optional<WalletSummary> currentWalletSummary = WalletManager.INSTANCE.getCurrentWalletSummary();

    Preconditions.checkState(currentWalletSummary.isPresent(), "'currentWalletSummary' must be present. No wallet is present.");

    WalletId walletId = currentWalletSummary.get().getWalletId();
    CharSequence password = currentWalletSummary.get().getWalletPassword().getPassword();

    return getOrCreateContactService(new WalletPassword(password, walletId));
  }

  /**
   * @param walletPassword The wallet ID for the wallet
   *
   * @return The contact service for a wallet
   */
  public static ContactService getOrCreateContactService(WalletPassword walletPassword) {

    log.debug("Get or create contact service");

    Preconditions.checkNotNull(walletPassword, "'walletPassword' must be present");
    Preconditions.checkNotNull(walletPassword.getWalletId(), "'walletId' must be present");
    Preconditions.checkNotNull(walletPassword.getPassword(), "'walletPassword' must be present");

    // Check if the contact service has been created for this wallet ID
    if (!contactService.isPresent()) {
      contactService = Optional.of(new PersistentContactService(walletPassword));
    }

    // Return the existing or new contact service
    return contactService.get();
  }

  /**
   * @return A hardware wallet service for the Trezor device if present
   */
  private static synchronized Optional<HardwareWalletService> createTrezorHardwareWalletService() {
    try {
      // Use factory to statically bind a specific hardware wallet
      TrezorV1HidHardwareWallet wallet = HardwareWallets.newUsbInstance(
        TrezorV1HidHardwareWallet.class,
        Optional.<Integer>absent(),
        Optional.<Integer>absent(),
        Optional.<String>absent()
      );
      // Wrap the hardware wallet in a suitable client to simplify message API
      HardwareWalletClient client = new TrezorHardwareWalletClient(wallet);

      // Wrap the client in a service for high level API suitable for downstream applications
      return Optional.of(new HardwareWalletService(client));

    } catch (Exception e) {
      log.warn("Could not create the hardware wallet.", e);
      return Optional.absent();
    }
  }

  /**
   * @return A hardware wallet service for the KeepKey device if present
   */
  private static synchronized Optional<HardwareWalletService> createKeepKeyHardwareWalletService() {
    try {
      // Use factory to statically bind a specific hardware wallet
      KeepKeyV1HidHardwareWallet wallet = HardwareWallets.newUsbInstance(
        KeepKeyV1HidHardwareWallet.class,
        Optional.<Integer>absent(),
        Optional.<Integer>absent(),
        Optional.<String>absent()
      );
      // Wrap the hardware wallet in a suitable client to simplify message API
      HardwareWalletClient client = new KeepKeyHardwareWalletClient(wallet);

      // Wrap the client in a service for high level API suitable for downstream applications
      return Optional.of(new HardwareWalletService(client));

    } catch (Exception e) {
      log.warn("Could not create the hardware wallet.", e);
      return Optional.absent();
    }
  }
}
