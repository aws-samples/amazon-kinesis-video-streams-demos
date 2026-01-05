const puppeteer = require('puppeteer');
const { CloudWatchClient } = require('@aws-sdk/client-cloudwatch');
const { CloudWatchMetrics } = require('./cloudwatch');
const fs = require('fs');

function log(message) {
  const timestamp = new Date().toISOString();
  console.log(`[${timestamp}] ${message}`);
}

class ViewerCanaryTest {
  constructor(config) {
    this.config = config;
    log(`Initializing test with single viewer`);
    
    this.sessionStartTime = Date.now();
    this.storageSessionJoined = false;
    this.screenshotTaken = false;
    this.framesReceived = false;
    this.testCompleted = false;
    this.timerStarted = false;
    
    this.browser = null;
    this.page = null;
  }

  async initializeCloudWatch() {
    const cwClient = new CloudWatchClient({ region: this.config.region || 'us-west-2' });
    CloudWatchMetrics.init(cwClient);
  }

  async createBrowser() {
    return await puppeteer.launch({ 
      headless: true,
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream']
    });
  }

  setupConsoleListener(page) {
    page.on('console', async (msg) => {
      const text = msg.text();
      log(`PAGE: ${text}`);
      
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
          'StorageSessionJoinTime',
          this.config.channelName,
          joinTime
        );
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
    });
    
    // Add forceTURN parameter if configured
    if (this.config.forceTURN === true) {
      params.set('forceTURN', 'true');
    }
    
    return `https://awslabs.github.io/amazon-kinesis-video-streams-webrtc-sdk-js/examples/index.html?${params}`;
  }

  async waitForChannel() {
    log('Waiting 2 minutes for master to create channel...');
    await new Promise(resolve => setTimeout(resolve, 120000));
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
    const maxRetries = 3;
    
    for (let retry = 0; retry < maxRetries; retry++) {
      if (retry > 0) {
        log(`Retry ${retry}: Restarting viewer...`);
        await page.click('#viewer-button');
      }
      
      const startTimeout = 60000;
      const startTime = Date.now();
      
      while ((Date.now() - startTime) < startTimeout) {
        const status = await page.evaluate(() => ({
          viewerExists: !!(window.viewer),
          connectionState: window.viewer?.pc?.iceConnectionState,
          signalingState: window.viewer?.pc?.signalingState,
          error: window.viewer?.error
        }));
        
        if (status.viewerExists && !status.error) {
          log('Viewer started successfully!');
          log(`Connection state: ${status.connectionState}`);
          log(`Signaling state: ${status.signalingState}`);
          return true;
        }
        
        if (status.error) {
          log('Viewer error detected, will retry...');
          break;
        }
        
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    }
    
    throw new Error('Viewer failed to start after 3 retries');
  }

  async waitForStorageSession() {
    log('Waiting for storage session to be joined...');
    const sessionTimeout = 180000;
    
    while (!this.storageSessionJoined && (Date.now() - this.sessionStartTime) < sessionTimeout) {
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    
    if (!this.storageSessionJoined) {
      throw new Error('Storage session not joined within 60 seconds');
    }
  }

  async getFrameStats(page) {
    return await page.evaluate(() => {
      const viewerExists = !!(window.viewer);
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
      
      if (window.viewer) {
        signalingConnected = window.viewer.signalingClient?.readyState === 1;
        storageSessionActive = !!(window.viewer.storageSessionJoined || 
                                window.viewer.isStorageSessionActive ||
                                (window.viewer.signalingClient && signalingConnected));
      }
      
      return {
        viewerExists,
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
      'TimeToFirstFrame',
      this.config.channelName,
      frameDetectionTime
    );
    
    this.framesReceived = true;
  }

  async monitorConnection(page) {
    log('Storage session joined! Now monitoring connection state...');
    let lastStatusLog = 0;
    const statusLogInterval = 10000;
    
    while (!this.testCompleted) {
      const frameStats = await this.getFrameStats(page);
      
      if (frameStats.hasActiveVideo && !this.framesReceived) {
        await this.handleVideoDetection(page);
      }
      
      if (frameStats.storageSessionActive && !this.timerStarted) {
        log('Storage session active!');
        this.timerStarted = true;
        
        log(`Storage session active, running test for 20 seconds...`);
        setTimeout(() => {
          this.testCompleted = true;
        }, 20000);
      }
      
      if (!frameStats.viewerExists) {
        log('Viewer object lost!');
        this.testCompleted = true;
        break;
      }
      
      const now = Date.now();
      if (now - lastStatusLog >= statusLogInterval) {
        const elapsed = Math.floor((now - this.sessionStartTime) / 1000);
        log(`[${elapsed}s]ActiveVideo: ${frameStats.hasActiveVideo}`);
        lastStatusLog = now;
      }
      
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }

  getTestResults() {
    const metrics = {
      success: this.storageSessionJoined,
      storageSessionJoined: this.storageSessionJoined,
      framesReceived: this.framesReceived,
      timestamp: Date.now()
    };
    
    log(metrics.success ? 'TEST PASSED: Storage session joined successfully' : 'TEST FAILED: Storage session not joined');
    log('Test completed!');
    log(`Final metrics: ${JSON.stringify(metrics)}`);
    
    const success = metrics.success && this.framesReceived;
    log(success ? 'TEST PASSED: Frames received successfully' : 'TEST FAILED: No frames received');
    
    return { ...metrics, success, framesReceived: this.framesReceived };
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
              if (window.viewer && window.viewer.signalingClient) {
                try {
                  window.viewer.signalingClient.close();
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
      
      // Close browser
      if (test.browser) {
        try {
          await test.browser.close();
          log(`Browser closed successfully`);
        } catch (error) {
          log(`Error closing browser: ${error.message}`);
        }
      }
      
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
        // await test.waitForChannel();
        
        // Create and initialize single browser and page
        log(`Creating viewer instance...`);
        
        test.browser = await test.createBrowser();
        test.page = await test.browser.newPage();
        
        test.setupConsoleListener(test.page);
        await test.initializePage(test.page);
        
        log('Starting viewer...');
        
        await test.waitForViewerStart(test.page);
        
        await test.waitForStorageSession();
        
        // Monitor the viewer
        await test.monitorConnection(test.page);
        
        cleanupCompleted = true; // Mark cleanup as not needed (normal completion)
        return test.getTestResults();
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
  duration: 360,
  saveFrames: process.env.SAVE_FRAMES === 'true',
  clientId: process.env.CLIENT_ID || `test-viewer-${Date.now()}`,
  viewerCount: process.env.VIEWER_COUNT || 1,
  forceTURN: process.env.FORCE_TURN === 'true'
}).then(result => {
  process.exit(result.success ? 0 : 1);
}).catch(error => {
  log(`Test failed with error: ${error.message}`);
  process.exit(1);
});
