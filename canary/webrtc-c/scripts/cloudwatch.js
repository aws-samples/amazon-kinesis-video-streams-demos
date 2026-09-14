const { CloudWatchClient, PutMetricDataCommand } = require("@aws-sdk/client-cloudwatch");
const { CloudWatchLogsClient, CreateLogGroupCommand, CreateLogStreamCommand, PutLogEventsCommand } = require("@aws-sdk/client-cloudwatch-logs");

class CloudWatchMetrics {
    static cwClient;
    
    static init(cwClient) {
        this.cwClient = cwClient;
    }
    
    static async publishMsMetric(metricName, channelName, msValue, when = Date.now()) {
        if (!this.cwClient || !channelName) {
            return msValue;
        }

        const dimensions = [{
            Name: 'StorageWithViewerChannelName',
            Value: channelName,
        }];
        
        // Add JobName dimension if available
        if (process.env.JOB_NAME) {
            dimensions.push({
                Name: 'JobName',
                Value: process.env.JOB_NAME
            });
        }
        
        // Add RunnerLabel dimension if available
        if (process.env.RUNNER_LABEL) {
            dimensions.push({
                Name: 'RunnerLabel',
                Value: process.env.RUNNER_LABEL
            });
        }

        const msMetric = {
            MetricName: metricName,
            Dimensions: dimensions,
            Timestamp: new Date(when),
            Value: msValue,
            Unit: 'Milliseconds'
        };

        const commandInput = {
            Namespace: 'ViewerApplication',
            MetricData: [msMetric],
        };

        const command = new PutMetricDataCommand(commandInput);
        try {
            await this.cwClient.send(command);
            console.log(metricName, 'sent to cloudwatch!');
        } catch (e) {
            console.error(e);
        }
        return msValue;
    }
    
    static async publishPercentageMetric(metricName, channelName, percentageValue, when = Date.now()) {
        if (!this.cwClient || !channelName) {
            return percentageValue;
        }

        const dimensions = [{
            Name: 'StorageWithViewerChannelName',
            Value: channelName,
        }];
        
        // Add JobName dimension if available
        if (process.env.JOB_NAME) {
            dimensions.push({
                Name: 'JobName',
                Value: process.env.JOB_NAME
            });
        }
        
        // Add RunnerLabel dimension if available
        if (process.env.RUNNER_LABEL) {
            dimensions.push({
                Name: 'RunnerLabel',
                Value: process.env.RUNNER_LABEL
            });
        }

        const percentageMetric = {
            MetricName: metricName,
            Dimensions: dimensions,
            Timestamp: new Date(when),
            Value: percentageValue,
            Unit: 'Percent'
        };

        const commandInput = {
            Namespace: 'ViewerApplication',
            MetricData: [percentageMetric],
        };

        const command = new PutMetricDataCommand(commandInput);
        try {
            await this.cwClient.send(command);
            console.log(metricName, 'percentage sent to cloudwatch!');
        } catch (e) {
            console.error(e);
        }
        return percentageValue;
    }
    
    static async publishCountMetric(metricName, channelName, countValue, when = Date.now()) {
        if (!this.cwClient || !channelName) {
            return countValue;
        }

        const dimensions = [{
            Name: 'StorageWithViewerChannelName',
            Value: channelName,
        }];
        
        // Add JobName dimension if available
        if (process.env.JOB_NAME) {
            dimensions.push({
                Name: 'JobName',
                Value: process.env.JOB_NAME
            });
        }
        
        // Add RunnerLabel dimension if available
        if (process.env.RUNNER_LABEL) {
            dimensions.push({
                Name: 'RunnerLabel',
                Value: process.env.RUNNER_LABEL
            });
        }

        const countMetric = {
            MetricName: metricName,
            Dimensions: dimensions,
            Timestamp: new Date(when),
            Value: countValue,
            Unit: 'Count'
        };

        const commandInput = {
            Namespace: 'ViewerApplication',
            MetricData: [countMetric],
        };

        const command = new PutMetricDataCommand(commandInput);
        try {
            await this.cwClient.send(command);
            console.log(metricName, 'count sent to cloudwatch!');
        } catch (e) {
            console.error(e);
        }
        return countValue;
    }
}

class CloudWatchLogger {
    static logsClient = null;
    static logGroupName = null;
    static logStreamName = null;
    static logBuffer = [];
    static flushInterval = null;
    static initialized = false;
    static flushing = false;

    static async init(region, logGroupName, logStreamName) {
        if (!logGroupName || !logStreamName) {
            console.log('CloudWatchLogger: No log group/stream configured, logging to stdout only');
            return;
        }

        // Re-entry guard. chrome-headless.js calls initializeCloudWatch() once per viewer
        // session, and under SOAK_MODE that is once per recycle segment -- so on a soak this
        // runs ~36 times a day, now always against the SAME group and stream because the
        // runner pins CANARY_LOG_STREAM_NAME per run instead of letting the Date.now()
        // fallback mint a new one per segment. Without this guard each of those calls
        // overwrote this.flushInterval with a fresh setInterval and orphaned the previous
        // one, leaking a timer per segment (~1080 over a 30-day soak). No events were lost --
        // every timer drains the same static buffer -- but each extra timer is one more 5s
        // PutLogEvents attempt contending for the single `flushing` flag, for no benefit.
        if (this.initialized && this.logGroupName === logGroupName && this.logStreamName === logStreamName) {
            return;
        }

        // A genuinely different destination (no current call site does this, but a future one
        // might): drain and stop the old timer before repointing, so the previous stream's
        // tail is flushed to the previous stream rather than appearing in the new one.
        if (this.initialized) {
            await this.shutdown();
            this.initialized = false;
        }

        this.logsClient = new CloudWatchLogsClient({ region });
        this.logGroupName = logGroupName;
        this.logStreamName = logStreamName;

        // Ensure log group exists
        try {
            await this.logsClient.send(new CreateLogGroupCommand({
                logGroupName: this.logGroupName,
            }));
            console.log(`CloudWatchLogger: Created log group ${this.logGroupName}`);
        } catch (e) {
            if (e.name === 'ResourceAlreadyExistsException') {
                // Expected — log group already exists
            } else {
                console.error(`CloudWatchLogger: Failed to create log group: ${e.message}`);
                return;
            }
        }

        // Create log stream
        try {
            await this.logsClient.send(new CreateLogStreamCommand({
                logGroupName: this.logGroupName,
                logStreamName: this.logStreamName,
            }));
            console.log(`CloudWatchLogger: Created log stream ${this.logStreamName}`);
        } catch (e) {
            if (e.name === 'ResourceAlreadyExistsException') {
                // Expected — log stream already exists
            } else {
                console.error(`CloudWatchLogger: Failed to create log stream: ${e.message}`);
                return;
            }
        }

        // Flush every 5 seconds
        this.flushInterval = setInterval(() => this.flush(), 5000);
        this.initialized = true;
        console.log(`CloudWatchLogger: Initialized — group=${this.logGroupName}, stream=${this.logStreamName}`);
    }

    static log(message) {
        if (!this.initialized) return;
        this.logBuffer.push({
            message: typeof message === 'string' ? message : JSON.stringify(message),
            timestamp: Date.now(),
        });
    }

    static async flush() {
        if (!this.initialized || this.logBuffer.length === 0 || this.flushing) return;

        this.flushing = true;
        const events = this.logBuffer.splice(0, this.logBuffer.length);

        // PutLogEvents requires events sorted by timestamp
        events.sort((a, b) => a.timestamp - b.timestamp);

        try {
            await this.logsClient.send(new PutLogEventsCommand({
                logGroupName: this.logGroupName,
                logStreamName: this.logStreamName,
                logEvents: events,
            }));
        } catch (e) {
            console.error(`CloudWatchLogger: Failed to push ${events.length} log events: ${e.message}`);
            // Put events back at the front of the buffer for retry
            this.logBuffer.unshift(...events);
        } finally {
            this.flushing = false;
        }
    }

    static async shutdown() {
        if (this.flushInterval) {
            clearInterval(this.flushInterval);
            this.flushInterval = null;
        }
        // Final flush to ensure all buffered logs are sent
        await this.flush();
        console.log('CloudWatchLogger: Shutdown complete');
    }
}

module.exports = { CloudWatchMetrics, CloudWatchLogger };
