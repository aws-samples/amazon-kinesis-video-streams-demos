const { CloudWatchClient, PutMetricDataCommand } = require("@aws-sdk/client-cloudwatch");

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

module.exports = { CloudWatchMetrics };
