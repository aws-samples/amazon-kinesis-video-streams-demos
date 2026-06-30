const puppeteer = require('puppeteer');
const { CloudWatchClient } = require('@aws-sdk/client-cloudwatch');
const { CloudWatchMetrics, CloudWatchLogger } = require('./cloudwatch');
const fs = require('fs');
const { execSync } = require('child_process');
const path = require('path');

function log(message) {
  const timestamp = new Date().toISOString();
  const formatted = `[${timestamp}] ${message}`;
  console.log(formatted);
  CloudWatchLogger.log(formatted);
}

// Capture unhandled errors and flush logs before dying
process.on('unhandledRejection', async (reason) => {
  log(`FATAL: Unhandled rejection: ${reason}`);
  await CloudWatchLogger.shutdown();
  process.exit(1);
});

process.on('uncaughtException', async (err) => {
  log(`FATAL: Uncaught exception: ${err.message}\n${err.stack}`);
  await CloudWatchLogger.shutdown();
  process.exit(1);
});

process.on('SIGTERM', async () => {
  log('Received SIGTERM — flushing logs before exit');
  await CloudWatchLogger.shutdown();
  process.exit(143);
});

class ViewerCanaryTest {
  constructor(config) {
    this.config = config;
    this.viewerId = process.env.VIEWER_ID || 'Viewer1';
    this.clientId = process.env.CLIENT_ID || `viewer-${Date.now()}`;
    this.metricSuffix = config.metricSuffix || '';
    
    log(`Initializing test with ${this.viewerId} (${this.clientId})${this.metricSuffix ? `, metric suffix: ${this.metricSuffix}` : ''}`);
    
    this.sessionStartTime = Date.now();
    this.storageSessionJoined = false;
    this.screenshotTaken = false;
    this.framesReceived = false;
    this.testCompleted = false;
    this.timerStarted = false;

    // Comprehensive timing tracking for WebRTC connection stages
    this.joinSSCallTime = null;
    this.offerReceivedTime = null;
    this.answerSentTime = null;
    this.firstIceCandidateReceivedTime = null;
    this.firstIceCandidateSentTime = null;
    this.allIceCandidatesGeneratedTime = null;
    this.peerConnectionEstablishedTime = null;
    
    // Flags to track first occurrences
    this.hasReceivedFirstIceCandidate = false;
    this.hasSentFirstIceCandidate = false;
    
    // ICE candidate type tracking
    this.firstHostCandidateTime = null;
    this.firstSrflxCandidateTime = null;
    this.firstRelayCandidateTime = null;
    this.hasGeneratedHostCandidate = false;
    this.hasGeneratedSrflxCandidate = false;
    this.hasGeneratedRelayCandidate = false;
    
    // WebRTC connection retry tracking (internal viewer retries)
    this.webrtcRetryCount = 0;
    this.hadWebRTCRetries = false;
    
    // Connection attempt tracking for ViewerJoinPercentage
    // Initial connection counts as attempt 1; each "Reconnecting..." adds another
    this.connectionAttempts = 1;
    this.successfulConnections = 0;
    
    // JoinStorageSessionAsViewer retry tracking
    this.joinSSRetryCount = 0;

    // Unexpected disconnect tracking — after peer connection was successfully established
    this.peerConnectionEstablished = false;
    this.unexpectedDisconnectCount = 0;

    // Viewer reconnect tracking — counts "[VIEWER] Reconnecting..." log lines
    this.viewerReconnectCount = 0;
    
    // Storage session join failure tracking - sequence detection
    // Failure is confirmed when these 3 logs appear consecutively:
    // 1. [VIEWER] Error joining storage session as viewer
    // 2. [VIEWER] Stopping VIEWER connection
    // 3. [VIEWER] Disconnected from signaling channel
    this.storageSessionJoinFailed = false;
    this.viewerDisconnected = false;
    this.failureSequenceStep = 0; // Track which step of the failure sequence we're at
    
    this.browser = null;
    this.page = null;

    // Video recording state
    this.isRecording = false;
    this.recordingFilePath = null;
    this.recordingWriteStream = null;
  }

  async initializeCloudWatch() {
    const region = this.config.region || 'us-west-2';
    const cwClient = new CloudWatchClient({ region });
    CloudWatchMetrics.init(cwClient);

    // Initialize CloudWatch Logs — uses same log group as the C master canary
    const logGroupName = process.env.CANARY_LOG_GROUP_NAME || '';
    const logStreamName = process.env.CANARY_LOG_STREAM_NAME ||
      `${process.env.RUNNER_LABEL || 'StorageViewer'}-${this.viewerId}-JSViewer-${Date.now()}`;
    await CloudWatchLogger.init(region, logGroupName, logStreamName);
  }

  // Helper to generate metric name with optional suffix
  getMetricName(baseName) {
    return `${baseName}_${this.viewerId}${this.metricSuffix}`;
  }

  // Helper function to extract ICE candidate type from candidate string
  extractIceCandidateType(text) {
    // Look for DEBUG log containing ICE candidate details
    if (!text.includes('[DEBUG] [VIEWER] ICE candidate:')) {
      return null;
    }
    
    // Extract candidate string from the log
    // Example: candidate:2220892825 1 udp 2122260223 192.168.1.142 55262 typ host generation 0 ufrag mnD0
    const candidateMatch = text.match(/candidate:.*?typ\s+(\w+)/);
    if (candidateMatch && candidateMatch[1]) {
      const candidateType = candidateMatch[1].toLowerCase();
      // Return standardized candidate types
      if (candidateType === 'host') return 'host';
      if (candidateType === 'srflx') return 'srflx';  
      if (candidateType === 'relay') return 'relay';
    }
    
    return null;
  }

  async createBrowser() {
    return await puppeteer.launch({ 
      headless: true,
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--allow-file-access-from-files']
    });
  }

  setupConsoleListener(page) {
    page.on('console', async (msg) => {
      // Serialize all arguments so objects show as JSON instead of [object Object]
      let text;
      try {
        const args = msg.args();
        const parts = await Promise.all(args.map(async (arg) => {
          try {
            const val = await arg.jsonValue();
            return typeof val === 'object' ? JSON.stringify(val) : String(val);
          } catch {
            return arg.toString();
          }
        }));
        text = parts.join(' ');
      } catch {
        text = msg.text();
      }
      log(`PAGE: ${text}`);
      
      // Track when JoinStorageSessionAsViewer API is called
      if (text.includes('[VIEWER] Joining storage session as viewer')) {
        this.joinSSCallTime = Date.now();
        log('JoinStorageSessionAsViewer call timestamp captured');
      }

      // Track SDP offer received — this means JoinStorageSessionAsViewer succeeded
      // (the media server sent an SDP offer back over the signaling channel)
      if (text.includes('[VIEWER] Received SDP offer from remote')) {
        this.offerReceivedTime = Date.now();
        log('SDP offer received timestamp captured');

        // Calculate and publish JoinSSCallToOfferReceived metric
        // Measures time from calling JoinStorageSessionAsViewer to receiving SDP offer
        if (this.joinSSCallTime && this.offerReceivedTime) {
          const joinSSToOfferTime = this.offerReceivedTime - this.joinSSCallTime;
          log(`JoinSSCallToOfferReceived time: ${joinSSToOfferTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('JoinSSCallToOfferReceived'),
            this.config.channelName,
            joinSSToOfferTime
          );
        }

        // Push JoinSSAsViewerAvailability = 1 (this attempt succeeded)
        log('JoinStorageSessionAsViewer attempt succeeded — pushing availability = 1');
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('JoinSSAsViewerAvailability'),
          this.config.channelName,
          1
        );

        // Push JoinSSAsViewerTimeout = 0 (this attempt did NOT time out)
        // This ensures CloudWatch has data points during healthy periods,
        // not just when timeouts occur.
        log('JoinStorageSessionAsViewer succeeded without timeout — pushing JoinSSAsViewerTimeout = 0');
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('JoinSSAsViewerTimeout'),
          this.config.channelName,
          0
        );
      }

      // Track JoinStorageSessionAsViewer retry failure — each retry is a failed attempt
      // This log means the API call did not result in an SDP offer from the media server
      if (text.includes('Did not receive SDP offer from Media Service. Retrying...')) {
        this.joinSSRetryCount++;
        log(`JoinStorageSessionAsViewer attempt failed (no SDP offer) — retry count: ${this.joinSSRetryCount}, pushing availability = 0`);
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('JoinSSAsViewerAvailability'),
          this.config.channelName,
          0
        );
      }

      // Track JoinStorageSession HTTP timeout — the API call did not complete within 6000ms
      // Log: "TimeoutError: Request did not complete within 6000 ms"
      // This fires once per timed-out API call attempt, before the retry log above
      if (text.includes('TimeoutError: Request did not complete within')) {
        log('JoinStorageSession API call timed out — pushing JoinSSAsViewerTimeout = 1');
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('JoinSSAsViewerTimeout'),
          this.config.channelName,
          1
        );
      }
      
      // Track SDP answer sent and calculate offer-to-answer metric
      if (text.includes('[VIEWER] Sending SDP answer to remote')) {
        this.answerSentTime = Date.now();
        log('SDP answer sent timestamp captured');
        
        // Calculate and publish the Offer Received to Answer Sent Time metric
        if (this.offerReceivedTime && this.answerSentTime) {
          const offerToAnswerTime = this.answerSentTime - this.offerReceivedTime;
          log(`Offer to Answer time: ${offerToAnswerTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('OfferReceivedToAnswerSentTime'),
            this.config.channelName,
            offerToAnswerTime
          );
        }
      }
      
      // Track first ICE candidate received from remote
      if (text.includes('[VIEWER] Received ICE candidate from remote') && !this.hasReceivedFirstIceCandidate) {
        this.firstIceCandidateReceivedTime = Date.now();
        this.hasReceivedFirstIceCandidate = true;
        log('First ICE candidate received timestamp captured');
        
        // Calculate answer-to-first-ice-received metric
        if (this.answerSentTime && this.firstIceCandidateReceivedTime) {
          const answerToFirstIceTime = this.firstIceCandidateReceivedTime - this.answerSentTime;
          log(`Answer Sent to First ICE Received time: ${answerToFirstIceTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('AnswerSentToFirstIceReceivedTime'),
            this.config.channelName,
            answerToFirstIceTime
          );
        }
      }
      
      // Track first ICE candidate sent to remote
      if (text.includes('[VIEWER] Sending ICE candidate to remote') && !this.hasSentFirstIceCandidate) {
        this.firstIceCandidateSentTime = Date.now();
        this.hasSentFirstIceCandidate = true;
        log('First ICE candidate sent timestamp captured');
        
        // Calculate first-ice-received-to-first-ice-sent metric
        if (this.firstIceCandidateReceivedTime && this.firstIceCandidateSentTime) {
          const firstIceReceivedToSentTime = this.firstIceCandidateSentTime - this.firstIceCandidateReceivedTime;
          log(`First ICE Received to First ICE Sent time: ${firstIceReceivedToSentTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('FirstIceReceivedToFirstIceSentTime'),
            this.config.channelName,
            firstIceReceivedToSentTime
          );
        }
      }
      
      // Track specific ICE candidate types (host, srflx, relay)
      const candidateType = this.extractIceCandidateType(text);
      if (candidateType && this.answerSentTime) {
        const currentTime = Date.now();
        
        if (candidateType === 'host' && !this.hasGeneratedHostCandidate) {
          this.firstHostCandidateTime = currentTime;
          this.hasGeneratedHostCandidate = true;
          const timeToHostCandidate = currentTime - this.answerSentTime;
          log(`First HOST candidate generated: ${timeToHostCandidate}ms after answer sent`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('TimeToFirstHostCandidate'),
            this.config.channelName,
            timeToHostCandidate
          );
        }
        
        if (candidateType === 'srflx' && !this.hasGeneratedSrflxCandidate) {
          this.firstSrflxCandidateTime = currentTime;
          this.hasGeneratedSrflxCandidate = true;
          const timeToSrflxCandidate = currentTime - this.answerSentTime;
          log(`First SRFLX candidate generated: ${timeToSrflxCandidate}ms after answer sent`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('TimeToFirstSrflxCandidate'),
            this.config.channelName,
            timeToSrflxCandidate
          );
        }
        
        if (candidateType === 'relay' && !this.hasGeneratedRelayCandidate) {
          this.firstRelayCandidateTime = currentTime;
          this.hasGeneratedRelayCandidate = true;
          const timeToRelayCandidate = currentTime - this.answerSentTime;
          log(`First RELAY candidate generated: ${timeToRelayCandidate}ms after answer sent`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('TimeToFirstRelayCandidate'),
            this.config.channelName,
            timeToRelayCandidate
          );
        }
      }
      
      // Track when all ICE candidates are generated
      if (text.includes('[VIEWER] All ICE candidates have been generated for remote')) {
        this.allIceCandidatesGeneratedTime = Date.now();
        log('All ICE candidates generated timestamp captured');
        
        // Calculate first-ice-sent-to-all-ice-generated metric
        if (this.firstIceCandidateSentTime && this.allIceCandidatesGeneratedTime) {
          const firstIceSentToAllGeneratedTime = this.allIceCandidatesGeneratedTime - this.firstIceCandidateSentTime;
          log(`First ICE Sent to All ICE Generated time: ${firstIceSentToAllGeneratedTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('FirstIceSentToAllIceGeneratedTime'),
            this.config.channelName,
            firstIceSentToAllGeneratedTime
          );
        }
      }
      
      // Track peer connection establishment
      if (text.includes('[VIEWER] Connection to peer successful!')) {
        this.peerConnectionEstablishedTime = Date.now();
        this.peerConnectionEstablished = true;
        this.successfulConnections++;
        log(`Peer connection established timestamp captured (successful connections: ${this.successfulConnections}/${this.connectionAttempts})`);

        // Push PeerConnectionAvailability = 1 (ICE candidate pair selected, connection established)
        log('Peer connection to media server succeeded — pushing PeerConnectionAvailability = 1');
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('PeerConnectionAvailability'),
          this.config.channelName,
          1
        );
        
        // Calculate all-ice-generated-to-connection-established metric
        if (this.allIceCandidatesGeneratedTime && this.peerConnectionEstablishedTime) {
          const allIceToConnectionTime = this.peerConnectionEstablishedTime - this.allIceCandidatesGeneratedTime;
          log(`All ICE Generated to Connection Established time: ${allIceToConnectionTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('AllIceGeneratedToConnectionEstablishedTime'),
            this.config.channelName,
            allIceToConnectionTime
          );
        }
        
        // Calculate total connection establishment time (offer to peer connection)
        if (this.offerReceivedTime && this.peerConnectionEstablishedTime) {
          const totalConnectionTime = this.peerConnectionEstablishedTime - this.offerReceivedTime;
          log(`Total Connection Establishment time (Offer to Peer Connection): ${totalConnectionTime}ms`);
          
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('TotalConnectionEstablishmentTime'),
            this.config.channelName,
            totalConnectionTime
          );
        }
      }
      
      // Track WebRTC connection retries and peer connection failure (30s timeout)
      if (text.includes('[VIEWER] Connection failed after 30 seconds, will enter retry.')) {
        this.webrtcRetryCount++;
        this.hadWebRTCRetries = true;
        log(`WebRTC connection retry detected! Total retries: ${this.webrtcRetryCount}`);

        // Push PeerConnectionAvailability = 0 (peer connection stuck in "connecting", timed out)
        log('Peer connection timed out after 30s — pushing PeerConnectionAvailability = 0');
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('PeerConnectionAvailability'),
          this.config.channelName,
          0
        );
      }

      // Track peer connection state explicitly transitioning to "failed" (ICE connectivity check failed)
      // This is mutually exclusive with the 30s timeout above — covers the case where ICE fails outright
      if (text.includes('[VIEWER] PeerConnection state: failed')) {
        if (this.peerConnectionEstablished) {
          // Connection was previously established — this is an unexpected disconnect.
          // The master's printPeerConnectionStateInfo only triggers onPeerConnectionFailed
          // (and reconnect) on 'failed', not on 'disconnected'. So 'failed' after 'connected'
          // is the only real unexpected disconnect we should track.
          this.unexpectedDisconnectCount++;
          log(`Unexpected disconnect detected (failed after connected)! Count: ${this.unexpectedDisconnectCount}`);
          await CloudWatchMetrics.publishCountMetric(
            this.getMetricName('UnexpectedDisconnect'),
            this.config.channelName,
            1
          );
        }

        // Always push PeerConnectionAvailability = 0 for any 'failed' state
        log('Peer connection state transitioned to FAILED — pushing PeerConnectionAvailability = 0');
        await CloudWatchMetrics.publishCountMetric(
          this.getMetricName('PeerConnectionAvailability'),
          this.config.channelName,
          0
        );
      }

      // Note: 'disconnected' state is intentionally NOT tracked as an unexpected disconnect.
      // The viewer's printPeerConnectionStateInfo only handles 'connected' and 'failed' —
      // 'disconnected' falls through without triggering onPeerConnectionFailed or any
      // reconnect logic. It's a transient ICE state that often resolves on its own.

      // Track viewer reconnect attempts — the master calls onPeerConnectionFailed which
      // logs "[VIEWER] Reconnecting..." before calling connectToMediaServer again.
      if (text.includes('[VIEWER] Reconnecting...')) {
        this.viewerReconnectCount++;
        this.connectionAttempts++;
        log(`Viewer reconnect attempt detected! Count: ${this.viewerReconnectCount}, total attempts: ${this.connectionAttempts}`);
      }
      
      // Detect storage session join error (single failure or retry failure)
      if (text.includes('[VIEWER] Error joining storage session as viewer')) {
        log('Storage session join error detected!');
        this.storageSessionJoinFailed = true;
        this.storageSessionJoined = false;
      }
      
      // Detect storage session join failure after all retries exhausted
      if (text.includes('Stopping the application after 5 failed attempts to connect to the storage session')) {
        log('Storage session join FAILED after 5 retry attempts!');
        this.storageSessionJoinFailed = true;
        this.storageSessionJoined = false;
      }
      
      // Detect viewer disconnection (signals end of failed session)
      if (text.includes('[VIEWER] Stopping VIEWER connection') || 
          text.includes('[VIEWER] Disconnected from signaling channel')) {
        log('Viewer disconnection detected!');
        this.viewerDisconnected = true;
      }
      
      if (text.includes('Successfully joined the storage session')) {
        log('Storage session joined - ready to monitor frames!');
        this.storageSessionJoined = true;
        
        if (this.config.saveScreenshots) {
          await page.screenshot({ path: `storage-session-active-${Date.now()}.png` });
          log('Screenshot saved!');
        }
        this.screenshotTaken = true;
        
        const joinTime = Date.now() - this.sessionStartTime;
        await CloudWatchMetrics.publishMsMetric(
          this.getMetricName('JoinSSAsViewerTime'),
          this.config.channelName,
          joinTime
        );

        // Publish JoinSSCallToSessionJoined metric — time from calling
        // JoinStorageSessionAsViewer to successfully joining the storage session
        if (this.joinSSCallTime) {
          const joinSSCallToSessionJoined = Date.now() - this.joinSSCallTime;
          log(`JoinSSCallToSessionJoined time: ${joinSSCallToSessionJoined}ms`);
          await CloudWatchMetrics.publishMsMetric(
            this.getMetricName('JoinSSCallToSessionJoined'),
            this.config.channelName,
            joinSSCallToSessionJoined
          );
        }
      }
    });
  }

  validateEnvironment() {
    if (!process.env.AWS_ACCESS_KEY_ID) {
      throw new Error('AWS_ACCESS_KEY_ID environment variable not set');
    }
  }

  buildTestUrl() {
    const params = new URLSearchParams({
      channelName: this.config.channelName,
      region: this.config.region || 'us-west-2',
      accessKeyId: process.env.AWS_ACCESS_KEY_ID,
      secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY,
      sessionToken: process.env.AWS_SESSION_TOKEN || '',
      sendVideo: this.config.sendVideo || 'false',
      sendAudio: this.config.sendAudio || 'false',
      useTrickleICE: 'true',
      endpoint: this.config.endpoint || '',
    });
    
    // Add forceTURN parameter if configured
    if (this.config.forceTURN === true) {
      params.set('forceTURN', 'true');
    }
    
    const defaultUrl = 'https://awslabs.github.io/amazon-kinesis-video-streams-webrtc-sdk-js/examples/index.html';
    const baseUrl = process.env.JS_PAGE_URL || defaultUrl;
    return `${baseUrl}?${params}`;
  }

  async initializePage(page) {
    const url = this.buildTestUrl();
    log(`Opening URL: ${url}`);
    
    await page.goto(url);
    await page.evaluate(() => {
      document.querySelector('#ingest-media-manual-on').setAttribute('data-selected', 'true');
    });
    
    log('Page loaded, clicking viewer button...');
    await page.click('#viewer-button');
  }

  async waitForViewerStart(page) {
    log('Waiting for viewer to start...');
    const startTimeout = 60000;
    const startTime = Date.now();
    
    while ((Date.now() - startTime) < startTimeout) {
      const status = await page.evaluate(() => {
        const m = typeof master !== 'undefined' ? master : window.master;
        return {
          viewerExists: !!(m),
          connectionState: m?.peerByClientId ? 
            Object.values(m.peerByClientId)[0]?.getPeerConnection?.()?.iceConnectionState : undefined,
          signalingState: m?.peerByClientId ?
            Object.values(m.peerByClientId)[0]?.getPeerConnection?.()?.signalingState : undefined,
          error: m?.error
        };
      });
      
      if (status.viewerExists && !status.error) {
        log('Viewer started successfully!');
        log(`Connection state: ${status.connectionState}`);
        log(`Signaling state: ${status.signalingState}`);
        return true;
      }
      
      if (status.error) {
        throw new Error(`Viewer error: ${status.error}`);
      }
      
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    
    throw new Error('Viewer failed to start within 60 seconds');
  }

  async waitForStorageSessionOrFailure() {
    log('Waiting for storage session to be joined or failure...');
    
    // Wait until either:
    // 1. Storage session is successfully joined
    // 2. Storage session join failed AND viewer disconnected (failure complete)
    // 3. Hard timeout reached
    const hardTimeout = this.config.duration * 1000;
    
    while ((Date.now() - this.sessionStartTime) < hardTimeout) {
      // Success case: storage session joined
      if (this.storageSessionJoined) {
        log('Storage session joined successfully!');
        return { success: true };
      }
      
      // Failure case: join failed and viewer disconnected
      if (this.storageSessionJoinFailed && this.viewerDisconnected) {
        log('Storage session join failed and viewer disconnected - failure complete');
        return { success: false, reason: 'join_failed' };
      }
      
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    
    // Timeout reached without success or complete failure
    log('Timeout reached waiting for storage session');
    return { success: false, reason: 'timeout' };
  }

  async getFrameStats(page) {
    return await page.evaluate(() => {
      const m = typeof master !== 'undefined' ? master : window.master;
      const masterExists = !!(m);
      const remoteViewElements = document.querySelectorAll('.remote-view');
      
      let hasActiveVideo = false;
      let videoInfo = [];
      
      remoteViewElements.forEach((video) => {
        if (video.srcObject && video.readyState >= 2) {
          hasActiveVideo = true;
          videoInfo.push({
            videoWidth: video.videoWidth,
            videoHeight: video.videoHeight,
            readyState: video.readyState,
          });
        }
      });
      
      let storageSessionActive = false;
      let signalingConnected = false;
      
      if (m) {
        const sigClient = m.channelHelper?.getSignalingClient?.();
        signalingConnected = sigClient?.readyState === 1;
        storageSessionActive = !!(signalingConnected && Object.keys(m.peerByClientId || {}).length > 0);
      }
      
      return {
        viewerExists: masterExists,
        storageSessionActive,
        signalingConnected,
        connectionState: signalingConnected ? 'connected' : 'disconnected',
        hasActiveVideo,
        remoteViewCount: remoteViewElements.length,
        videoInfo,
        timestamp: Date.now()
      };
    });
  }

  /**
   * Collect inbound-rtp stats from the viewer's RTCPeerConnection via getStats().
   * Returns { packetsReceived, packetsLost, framesDecoded, framesDropped, jitter }
   * or null if the peer connection is not available.
   */
  async getRTCStats(page) {
    return await page.evaluate(async () => {
      // The KVS JS SDK example page stores peer connections in master.peerByClientId
      // regardless of whether it's running as master or viewer role.
      // master is declared with `let` so it may not be on `window`.
      const m = typeof master !== 'undefined' ? master : window.master;
      const peerMap = m?.peerByClientId;
      if (!peerMap) return { debug: `no master.peerByClientId (master exists: ${!!m})` };

      const clientIds = Object.keys(peerMap);
      if (clientIds.length === 0) return { debug: 'peerByClientId is empty' };

      const peer = peerMap[clientIds[0]];
      const pc = typeof peer.getPeerConnection === 'function' ? peer.getPeerConnection() : peer;
      if (!pc || typeof pc.getStats !== 'function') {
        return { debug: `peer found but no getStats. peer keys: [${Object.keys(peer).slice(0, 20)}]` };
      }

      const report = await pc.getStats();
      const result = { video: null, audio: null };
      report.forEach(stat => {
        if (stat.type !== 'inbound-rtp') return;
        const entry = {
          packetsReceived: stat.packetsReceived || 0,
          packetsLost: stat.packetsLost || 0,
          bytesReceived: stat.bytesReceived || 0,
          jitter: stat.jitter || 0,
          framesDecoded: stat.framesDecoded || 0,
          framesDropped: stat.framesDropped || 0,
          framesPerSecond: stat.framesPerSecond || 0,
          nackCount: stat.nackCount || 0,
          pliCount: stat.pliCount || 0,
        };
        if (stat.kind === 'video') result.video = entry;
        else if (stat.kind === 'audio') result.audio = entry;
      });
      return result;
    }).catch((err) => {
      log(`getRTCStats error: ${err.message}`);
      return null;
    });
  }

  async captureVideoFrame(page) {
    return await page.evaluate(() => {
      const remoteVideo = document.querySelector('.remote-view');
      if (remoteVideo && remoteVideo.videoWidth > 0) {
        const canvas = document.createElement('canvas');
        canvas.width = remoteVideo.videoWidth;
        canvas.height = remoteVideo.videoHeight;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(remoteVideo, 0, 0);
        return {
          dataURL: canvas.toDataURL('image/png'),
          width: canvas.width,
          height: canvas.height
        };
      }
      return null;
    });
  }

  async startRecording(page) {
    if (this.isRecording) return;

    const recordingsDir = 'recordings';
    if (!fs.existsSync(recordingsDir)) fs.mkdirSync(recordingsDir);

    this.recordingFilePath = `${recordingsDir}/viewer-${Date.now()}.mp4`;
    this.recordingWriteStream = fs.createWriteStream(this.recordingFilePath);
    this.isRecording = true;

    // Expose a Node function so the browser can stream video chunks to disk.
    // Each chunk arrives as an array of byte values since Blobs can't cross the bridge.
    await page.exposeFunction('_saveVideoChunk', (bytes) => {
      if (this.recordingWriteStream) {
        this.recordingWriteStream.write(Buffer.from(bytes));
      }
    });

    const mimeType = await page.evaluate(() => {
      const remoteVideo = document.querySelector('.remote-view');
      if (!remoteVideo || !remoteVideo.srcObject) {
        console.log('No remote video stream available for recording');
        return null;
      }

      // Use MP4/H.264 as the primary format if supported, fall back to WebM/VP8.
      // The ArrayBuffer-based data transfer ensures bytes are preserved correctly.
      const candidates = [
        'video/mp4; codecs=avc1',
        'video/mp4',
        'video/webm; codecs=vp8',
        'video/webm'
      ];
      let selectedMime = null;
      for (const mime of candidates) {
        if (MediaRecorder.isTypeSupported(mime)) {
          selectedMime = mime;
          break;
        }
      }
      if (!selectedMime) {
        console.log('No supported MIME type found for MediaRecorder');
        return null;
      }

      const stream = remoteVideo.srcObject;
      const recorder = new MediaRecorder(stream, { mimeType: selectedMime });

      recorder.ondataavailable = async (event) => {
        if (event.data && event.data.size > 0) {
          // Convert Blob to ArrayBuffer, then to an array of numbers to cross the Puppeteer bridge
          const arrayBuffer = await event.data.arrayBuffer();
          const bytes = Array.from(new Uint8Array(arrayBuffer));
          window._saveVideoChunk(bytes);
        }
      };

      recorder.onstop = () => {
        console.log('MediaRecorder stopped');
      };

      // Store on window so we can stop it later
      window._mediaRecorder = recorder;
      // Record in 1-second chunks for incremental flushing
      recorder.start(1000);
      console.log(`MediaRecorder started with MIME type: ${selectedMime}`);
      return selectedMime;
    });

    if (mimeType) {
      // Fix file extension if the actual MIME type doesn't match the default .mp4
      if (mimeType.startsWith('video/webm')) {
        const newPath = this.recordingFilePath.replace(/\.mp4$/, '.webm');
        this.recordingWriteStream.close();
        fs.renameSync(this.recordingFilePath, newPath);
        this.recordingFilePath = newPath;
        this.recordingWriteStream = fs.createWriteStream(this.recordingFilePath, { flags: 'a' });
      }
      log(`Recording started: ${this.recordingFilePath} (${mimeType})`);
    } else {
      log('Failed to start recording — no supported MIME type or no stream');
      this.isRecording = false;
      this.recordingWriteStream.close();
      this.recordingWriteStream = null;
    }
  }

  async stopRecording(page) {
    if (!this.isRecording) return;

    try {
      // Stop the MediaRecorder in the browser and wait for the final chunk
      await page.evaluate(() => {
        return new Promise((resolve) => {
          if (window._mediaRecorder && window._mediaRecorder.state !== 'inactive') {
            window._mediaRecorder.onstop = () => resolve();
            window._mediaRecorder.stop();
          } else {
            resolve();
          }
        });
      });

      // Give a moment for the last chunk to flush through the bridge
      await new Promise(resolve => setTimeout(resolve, 500));
    } catch (error) {
      log(`Error stopping MediaRecorder: ${error.message}`);
    }

    if (this.recordingWriteStream) {
      this.recordingWriteStream.end();
      this.recordingWriteStream = null;
    }
    this.isRecording = false;
    log(`Recording saved: ${this.recordingFilePath}`);
  }

  async runVideoVerification() {
    if (!this.recordingFilePath || !fs.existsSync(this.recordingFilePath)) {
      log('No recording available for video verification, skipping');
      return;
    }

    const scriptDir = path.dirname(process.argv[1] || __filename);
    const verifyScript = path.join(scriptDir, 'video-verification', 'verify.py');
    const sourceFrames = path.join(scriptDir, '..', 'assets', 'h264SampleFrames');

    if (!fs.existsSync(verifyScript)) {
      log('verify.py not found, skipping video verification');
      return;
    }

    try {
      log('Running video verification...');
      // Prefer the venv Python if available (PEP 668 blocks system-wide pip on modern Ubuntu)
      const venvPython = path.join(process.env.HOME || '', '.venv', 'video-verify', 'bin', 'python3');
      if (!fs.existsSync(venvPython)) {
        log(`Video verification venv not found at ${venvPython} — run setup-storage-viewer.sh first`);
        return;
      }
      const cmd = `"${venvPython}" "${verifyScript}" --recording "${this.recordingFilePath}" --source-frames "${sourceFrames}" --json`;
      const output = execSync(cmd, { encoding: 'utf-8', timeout: 600000 });
      const results = JSON.parse(output.trim());

      log(`Video verification results: availability=${results.storage_availability}, avg SSIM=${results.avg_ssim}, min SSIM=${results.min_ssim}, max SSIM=${results.max_ssim}, compared=${results.frames_compared}`);

      await CloudWatchMetrics.publishCountMetric(
        this.getMetricName('ViewerStorageAvailability'),
        this.config.channelName,
        results.storage_availability
      );

      if (results.avg_ssim !== undefined) {
        await CloudWatchMetrics.publishPercentageMetric(
          this.getMetricName('ViewerSSIMAvg'),
          this.config.channelName,
          results.avg_ssim * 100
        );
        await CloudWatchMetrics.publishPercentageMetric(
          this.getMetricName('ViewerSSIMMin'),
          this.config.channelName,
          results.min_ssim * 100
        );
        await CloudWatchMetrics.publishPercentageMetric(
          this.getMetricName('ViewerSSIMMax'),
          this.config.channelName,
          results.max_ssim * 100
        );
      }
    } catch (error) {
      log(`Video verification failed: ${error.message}`);
    }

    // Preserve recording for manual verification
    if (this.recordingFilePath && fs.existsSync(this.recordingFilePath)) {
      log(`Viewer recording preserved at: ${this.recordingFilePath}`);
    }
  }

  async handleVideoDetection(page) {
    log('SUCCESS: Active video detected!');
    
    const frameData = await this.captureVideoFrame(page);
    if (frameData) {
      log(`First frame detected: ${frameData.width}x${frameData.height}`);
      
      if (this.config.saveFrames) {
        if (!fs.existsSync('frames')) fs.mkdirSync('frames');
        const base64Data = frameData.dataURL.replace(/^data:image\/png;base64,/, '');
        fs.writeFileSync(`frames/first-frame-${Date.now()}.png`, base64Data, 'base64');
        log('First frame saved!');
      }
    }
    
    const frameDetectionTime = Date.now() - this.sessionStartTime;
    await CloudWatchMetrics.publishMsMetric(
      this.getMetricName('TimeToFirstFrame'),
      this.config.channelName,
      frameDetectionTime
    );
    
    this.framesReceived = true;

    // Start recording the viewer's video stream
    await this.startRecording(page);
  }

  async monitorConnection(page) {
    log('Storage session joined! Now monitoring connection state...');
    
    // Start timer immediately since we already joined the storage session
    this.timerStarted = true;
    const monitorDurationMs = 156000; // 2 min 36 sec
    log(`Storage session joined, monitoring connection for ${monitorDurationMs / 1000} seconds...`);
    setTimeout(() => {
      this.testCompleted = true;
    }, monitorDurationMs);
    
    let lastStatusLog = 0;
    const statusLogInterval = 10000;
    
    // Track RTCStats snapshots for final packet loss calculation
    this.firstRTCStats = null;
    this.lastRTCStats = null;
    
    while (!this.testCompleted) {
      const frameStats = await this.getFrameStats(page);
      
      if (frameStats.hasActiveVideo && !this.framesReceived) {
        await this.handleVideoDetection(page);
      }
      
      if (!frameStats.viewerExists) {
        log('Viewer object lost!');
        this.testCompleted = true;
        break;
      }
      
      // Collect RTCStats snapshot
      const rtcStats = await this.getRTCStats(page);
      if (rtcStats?.video) {
        if (!this.firstRTCStats) this.firstRTCStats = rtcStats;
        this.lastRTCStats = rtcStats;
      } else if (rtcStats?.debug && !this.rtcStatsDebugLogged) {
        log(`RTCStats debug: ${rtcStats.debug}`);
        this.rtcStatsDebugLogged = true;
      }
      
      const now = Date.now();
      if (now - lastStatusLog >= statusLogInterval) {
        const elapsed = Math.floor((now - this.sessionStartTime) / 1000);
        let statusMsg = `[${elapsed}s]ActiveVideo: ${frameStats.hasActiveVideo}`;
        if (rtcStats?.video) {
          const v = rtcStats.video;
          statusMsg += ` | pktsRecv:${v.packetsReceived} pktsLost:${v.packetsLost} fps:${v.framesPerSecond} jitter:${(v.jitter * 1000).toFixed(1)}ms`;
        }
        log(statusMsg);
        lastStatusLog = now;
      }
      
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }

  async getTestResults() {
    const metrics = {
      success: this.storageSessionJoined,
      storageSessionJoined: this.storageSessionJoined,
      framesReceived: this.framesReceived,
      timestamp: Date.now()
    };
    
    log(metrics.success ? 'TEST PASSED: Storage session joined successfully' : 'TEST FAILED: Storage session not joined');
    log('Test completed!');
    log(`Final metrics: ${JSON.stringify(metrics)}`);
    
    // Publish success rate metric (100% for success, 0% for failure)
    const successRate = this.storageSessionJoined ? 100 : 0;
    await CloudWatchMetrics.publishPercentageMetric(
      this.getMetricName('StorageSessionSuccessRate'),
      this.config.channelName,
      successRate
    );
    
    // Publish WebRTC connection retry count metric
    await CloudWatchMetrics.publishCountMetric(
      this.getMetricName('WebRTCConnectionRetryCount'),
      this.config.channelName,
      this.webrtcRetryCount
    );
    
    log(`WebRTC Connection Retry Summary: Total retries: ${this.webrtcRetryCount}, Had retries: ${this.hadWebRTCRetries}`);
    
    // Publish JoinStorageSessionAsViewer retry count (no SDP offer received, had to retry)
    await CloudWatchMetrics.publishCountMetric(
      this.getMetricName('JoinSSAsViewerRetryCount'),
      this.config.channelName,
      this.joinSSRetryCount
    );
    log(`JoinSSAsViewer Retry Summary: Total retries: ${this.joinSSRetryCount}`);
    
    // Publish unexpected disconnect count (0 means stable connection throughout monitoring)
    await CloudWatchMetrics.publishCountMetric(
      this.getMetricName('UnexpectedDisconnectCount'),
      this.config.channelName,
      this.unexpectedDisconnectCount
    );
    log(`Unexpected Disconnect Summary: Total disconnects after connection: ${this.unexpectedDisconnectCount}`);
    
    // Publish viewer reconnect count (0 means no reconnects during the session)
    await CloudWatchMetrics.publishCountMetric(
      this.getMetricName('ViewerReconnectCount'),
      this.config.channelName,
      this.viewerReconnectCount
    );
    log(`Viewer Reconnect Summary: Total reconnects: ${this.viewerReconnectCount}`);
    
    // Publish packet loss rate from RTCStatsReport (inbound-rtp)
    if (this.lastRTCStats?.video) {
      const v = this.lastRTCStats.video;
      const totalPackets = v.packetsReceived + v.packetsLost;
      const packetLossRate = totalPackets > 0 ? (v.packetsLost / totalPackets) * 100 : 0;
      
      log(`RTCStats Video Summary: packetsReceived=${v.packetsReceived}, packetsLost=${v.packetsLost}, packetLossRate=${packetLossRate.toFixed(2)}%`);
      log(`RTCStats Video Detail: framesDecoded=${v.framesDecoded}, framesDropped=${v.framesDropped}, nackCount=${v.nackCount}, pliCount=${v.pliCount}`);
      
      await CloudWatchMetrics.publishPercentageMetric(
        this.getMetricName('ViewerPacketLossRate'),
        this.config.channelName,
        packetLossRate
      );
      
      await CloudWatchMetrics.publishCountMetric(
        this.getMetricName('ViewerFramesDropped'),
        this.config.channelName,
        v.framesDropped
      );
      
      await CloudWatchMetrics.publishCountMetric(
        this.getMetricName('ViewerNackCount'),
        this.config.channelName,
        v.nackCount
      );
      
      await CloudWatchMetrics.publishCountMetric(
        this.getMetricName('ViewerPliCount'),
        this.config.channelName,
        v.pliCount
      );
    } else {
      log('No RTCStats video data available for packet loss calculation');
    }
    
    // Publish connection attempt stats for ViewerJoinPercentage aggregation
    const joinPct = this.connectionAttempts > 0
      ? (this.successfulConnections * 100.0) / this.connectionAttempts
      : 0;
    log(`Connection Attempt Summary: ${this.successfulConnections}/${this.connectionAttempts} successful (${joinPct.toFixed(1)}%)`);
    log(`VIEWER_STATS:${JSON.stringify({attempts: this.connectionAttempts, successes: this.successfulConnections})}`);
    
    // Success is based on storage session joining, not frame reception
    const success = this.storageSessionJoined;
    log(success ? 'TEST PASSED: Storage session joined successfully' : 'TEST FAILED: Storage session not joined');
    
    return { ...metrics, success };
  }
}

async function runViewerCanary(config) {
  log('Starting viewer canary test...');
  log(`Config: ${JSON.stringify(config)}`);
  
  const test = new ViewerCanaryTest(config);
  
  // Cleanup function to properly close viewer
  const cleanup = async (reason = 'normal') => {
    try {
      log(`Cleaning up viewer connection (${reason})...`);
      
      // Stop recording before closing the viewer
      if (test.isRecording && test.page) {
        await test.stopRecording(test.page);
      }

      if (test.page) {
        try {
          // Try to click stop viewer button for proper cleanup
          const stopButtonClicked = await test.page.evaluate(() => {
            const stopButton = document.querySelector('#stop-viewer-button');
            if (stopButton && !stopButton.disabled) {
              stopButton.click();
              return true;
            }
            return false;
          }).catch(() => false);
          
          if (stopButtonClicked) {
            log(`Stop viewer button clicked successfully`);
          } else {
            log(`Stop viewer button not found, attempting manual cleanup`);
            // Manual cleanup if button doesn't exist
            await test.page.evaluate(() => {
              const m = typeof master !== 'undefined' ? master : window.master;
              if (m?.channelHelper) {
                try {
                  const sigClient = m.channelHelper.getSignalingClient?.();
                  if (sigClient) sigClient.close();
                  console.log('Manually closed signaling client');
                } catch (e) {
                  console.log('Error closing signaling client:', e);
                }
              }
            }).catch(() => {});
          }
        } catch (error) {
          log(`Error cleaning up viewer: ${error.message}`);
        }
      }
      
      // Wait a moment for cleanup
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      // Close browser BEFORE running video verification to prevent
      // stale connection events (disconnects, reconnects) during the
      // potentially long verification process.
      if (test.browser) {
        try {
          await test.browser.close();
          log(`Browser closed successfully`);
        } catch (error) {
          log(`Error closing browser: ${error.message}`);
        }
      }

      // Run video verification after browser is closed
      await test.runVideoVerification();
      
    } catch (error) {
      log(`Error during cleanup: ${error.message}`);
    }
  };

  // Two-stage timeout approach: cleanup timeout + hard timeout
  let cleanupCompleted = false;
  
  const performCleanup = async () => {
    if (cleanupCompleted) return;
    cleanupCompleted = true;
    
    try {
      log('Timeout approaching - performing cleanup...');
      
      // Try to stop viewer
      if (test.page) {
        try {
          await test.page.click('#stop-viewer-button');
          log(`Stop Viewer button clicked successfully`);
        } catch (error) {
          log(`Cleanup failed: ${error.message}`);
        }
      }
      
      await new Promise(resolve => setTimeout(resolve, 1000)); // Wait for cleanup
    } catch (error) {
      log(`Cleanup failed: ${error.message}`);
    }
  };

  const cleanupTimeout = (config.duration - 10) * 1000; // Start cleanup 10 seconds early
  const hardTimeout = config.duration * 1000; // Hard timeout

  const cleanupPromise = new Promise((_, reject) => {
    setTimeout(async () => {
      await performCleanup();
      reject(new Error('Test timeout - cleanup completed'));
    }, cleanupTimeout);
  });

  const hardTimeoutPromise = new Promise((_, reject) => {
    setTimeout(() => {
      reject(new Error('Hard timeout exceeded'));
    }, hardTimeout);
  });
  
  try {
    const results = await Promise.race([
      (async () => {
        await test.initializeCloudWatch();
        test.validateEnvironment();
        
        // Create and initialize single browser and page
        log(`Creating viewer instance...`);
        
        test.browser = await test.createBrowser();
        test.page = await test.browser.newPage();
        
        test.setupConsoleListener(test.page);
        await test.initializePage(test.page);
        
        log('Starting viewer...');
        
        await test.waitForViewerStart(test.page);
        
        // Wait for storage session join or failure
        const sessionResult = await test.waitForStorageSessionOrFailure();
        
        if (sessionResult.success) {
          // Success: monitor connection briefly then complete
          await test.monitorConnection(test.page);
        } else {
          // Failure: session result already captured, just log
          log(`Storage session failed: ${sessionResult.reason}`);
        }
        
        cleanupCompleted = true; // Mark cleanup as not needed (normal completion)
        return await test.getTestResults();
      })(),
      cleanupPromise,
      hardTimeoutPromise
    ]);
    
    await cleanup('normal');
    return results;
    
  } catch (error) {
    log(`Error running viewer canary: ${error.message}`);
    
    // If cleanup hasn't been performed yet (e.g., hard timeout), do it now
    if (!cleanupCompleted) {
      await performCleanup();
    }
    
    const isTimeout = error.message.includes('timeout');
    await cleanup(isTimeout ? 'timeout' : 'error');
    throw error;
  }
}

// Run the test
runViewerCanary({
  channelName: process.env.CANARY_CHANNEL_NAME || 'ScaryTestStream',
  region: process.env.AWS_REGION || 'us-west-2',
  duration: parseInt(process.env.TEST_DURATION) || 180,
  saveFrames: process.env.SAVE_FRAMES === 'true',
  clientId: process.env.CLIENT_ID || `test-viewer-${Date.now()}`,
  forceTURN: process.env.FORCE_TURN === 'true',
  endpoint: process.env.ENDPOINT || '',
  metricSuffix: process.env.METRIC_SUFFIX || ''
}).then(async (result) => {
  await CloudWatchLogger.shutdown();
  process.exit(result.success ? 0 : 1);
}).catch(async (error) => {
  log(`Test failed with error: ${error.message}`);
  await CloudWatchLogger.shutdown();
  process.exit(1);
});
