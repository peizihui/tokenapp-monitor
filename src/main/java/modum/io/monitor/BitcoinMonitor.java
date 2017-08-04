package modum.io.monitor;

import static org.bitcoinj.core.TransactionConfidence.ConfidenceType.*;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.Listener;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bitconin SPV wallet that scans the blockchain transactions for watched addresses.
 * Keeps track of the total amount in USD send to any watched address.
 */
public class BitcoinMonitor {
  private final static Logger LOG = LoggerFactory.getLogger(BitcoinMonitor.class);

  private final Context context;
  private final NetworkParameters chainParams;
  private final Wallet wallet;
  private final PeerGroup peerGroup;
  private final SPVBlockStore blockStore;
  private final ExchangeRateService fxService;
  private Set<TransactionOutput> processedUTXOs = new HashSet<>();
  private BigDecimal totalRaised = BigDecimal.ZERO;

  public BitcoinMonitor(ExchangeRateService fxService, String bitcoinNetwork) throws Exception {
    this.fxService = fxService;
    chainParams = BitcoinNet.getNetworkParams(BitcoinNet.of(bitcoinNetwork));
    context = new Context(chainParams);
    File blockStoreFile = Files.createTempFile("chain", "tmp").toFile();
    blockStoreFile.deleteOnExit();
    if (blockStoreFile.exists()) blockStoreFile.delete();
    wallet = new Wallet(context);
    blockStore = new SPVBlockStore(chainParams, blockStoreFile);
    // TODO: Checkpointing to speed up start-up phase
    BlockChain blockChain = new BlockChain(context, blockStore);
    peerGroup = new PeerGroup(context, blockChain);
    blockChain.addWallet(wallet);
    peerGroup.addWallet(wallet);

    // Regtest has no peer-to-peer functionality
    if (!chainParams.equals(RegTestParams.get()))
      peerGroup.addPeerDiscovery(new DnsDiscovery(chainParams));

    addCoinsReceivedListener();
  }

  /**
   * Add an address we want to monitor
   * @param addressString Bitcoin address in Base58 String
   * @param timestamp Timestamp in seconds when this address was created
   */
  public void addMonitoredAddress(String addressString, long timestamp) {
    LOG.info("Add monitored Bitcoin Address: {}", addressString);
    Address address = Address.fromBase58(chainParams, addressString);
    wallet.addWatchedAddress(address, timestamp);
  }

  public Long getTotalRaisedUSD() {
    return totalRaised.setScale(0, BigDecimal.ROUND_UP).longValue();
  }

  public void start() throws InterruptedException {
    peerGroup.start();

    // Download block chain (blocking)
    final DownloadProgressTracker downloadListener = new DownloadProgressTracker() {
      @Override
      protected void doneDownload() {
        LOG.info("Download done");
      }
      @Override
      protected void progress(double pct, int blocksSoFar, Date date) {
        LOG.info("Downloading chain: {}%", (int) pct);
      }
    };
    peerGroup.startBlockChainDownload(downloadListener);
    LOG.info("Downloading SPV blockchain...");
    downloadListener.await();
  }

  /**
   * Listens for changes to watched addresses
   */
  private void addCoinsReceivedListener() {
    wallet.addCoinsReceivedEventListener((wallet1, tx, prevBalance, newBalance) -> {
      Context.propagate(context);
      // Check outputs
      tx.getOutputs().forEach(utxo -> {

        // If not already processed and this output sends to one of our watched addresses
        if (!processedUTXOs.contains(utxo) && utxo.getScriptPubKey().isSentToAddress()) {
          Address address = utxo.getAddressFromP2PKHScript(chainParams);
          if (wallet1.getWatchedAddresses().contains(address)) {

            // If the confidence is already BUILDING (1 block or more on best chain)
            // we have a hit
            if (BitcoinUtils.isBuilding(tx)) {
              coinsReceived(utxo);

              // If pending or unknown we add a confidence changed listener and wait for block inclusion
            } else if (BitcoinUtils.isPending(tx) || BitcoinUtils.isUnknown(tx)) {
              LOG.info("Pending: {} satoshi received in {}", utxo.getValue(), tx.getHashAsString());
              Listener listener = new Listener() {
                @Override
                public void onConfidenceChanged(TransactionConfidence confidence, ChangeReason reason) {
                  if (!processedUTXOs.contains(utxo)) {
                    if (confidence.getConfidenceType().equals(BUILDING)) {
                      coinsReceived(utxo);
                      tx.getConfidence().removeEventListener(this);
                    } else if (confidence.getConfidenceType().equals(DEAD) || confidence
                        .getConfidenceType().equals(IN_CONFLICT)) {
                      tx.getConfidence().removeEventListener(this);
                    }
                  }
                }
              };
              tx.getConfidence().addEventListener(listener);
            }
          }
        }
      });
    });
  }

  /**
   * We have some funds send to us. This is called live or when catching-up at startup.
   * @param utxo The transaction output we received
   */
  private void coinsReceived(TransactionOutput utxo) {
    long satoshi = utxo.getValue().getValue();

    long blockHeight = utxo.getParentTransaction().getConfidence().getAppearedAtChainHeight();

    BigDecimal USDperBTC = null;
    try {
      USDperBTC = fxService.getUSDPerBTC(blockHeight);
    } catch (SQLException e) {
      LOG.error("Could not fetch exchange rate for utxo in tx {} with satoshi value {}. {} {}",
          utxo.getParentTransaction().getHashAsString(), satoshi, e.getMessage(), e.getCause());
    }
    BigDecimal usdReceived = BigDecimal.valueOf(satoshi)
        .multiply(USDperBTC)
        .divide(BigDecimal.valueOf(100_000_000L), BigDecimal.ROUND_DOWN);

    LOG.info("Received {} USD / {} satoshi / {} blockHeight / {} fx-rate / {} txid",
        usdReceived,
        utxo.getValue(),
        blockHeight,
        USDperBTC,
        utxo.getParentTransaction().getHashAsString());

    processedUTXOs.add(utxo);
    totalRaised = totalRaised.add(usdReceived);
  }

}
