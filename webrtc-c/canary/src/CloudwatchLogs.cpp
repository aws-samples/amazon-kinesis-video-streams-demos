#include "Include.h"

namespace Canary {

CloudwatchLogs::CloudwatchLogs(PConfig pConfig, ClientConfiguration* pClientConfig) : pConfig(pConfig), client(*pClientConfig)
{
}

STATUS CloudwatchLogs::init()
{
    STATUS retStatus = STATUS_SUCCESS;
    CreateLogGroupRequest createLogGroupRequest;
    Aws::CloudWatchLogs::Model::CreateLogStreamOutcome createLogStreamOutcome;
    CreateLogStreamRequest createLogStreamRequest;

    createLogGroupRequest.SetLogGroupName(pConfig->logGroupName.value);
    // ignore error since if this operation fails, CreateLogStream should fail as well.
    // There might be some errors that can lead to successfull CreateLogStream, e.g. log group already exists.
    this->client.CreateLogGroup(createLogGroupRequest);

    createLogStreamRequest.SetLogGroupName(pConfig->logGroupName.value);
    createLogStreamRequest.SetLogStreamName(pConfig->logStreamName.value);
    createLogStreamOutcome = this->client.CreateLogStream(createLogStreamRequest);

    CHK_ERR(createLogStreamOutcome.IsSuccess(), STATUS_INVALID_OPERATION, "Failed to create \"%s\" log stream: %s",
            pConfig->logStreamName.value.c_str(), createLogStreamOutcome.GetError().GetMessage().c_str());

CleanUp:

    return retStatus;
}

VOID CloudwatchLogs::deinit()
{
    this->flush(TRUE);
}

VOID CloudwatchLogs::push(string log)
{
    std::lock_guard<std::recursive_mutex> lock(this->sync.mutex);
    Aws::String awsCwString(log.c_str(), log.size());
    auto logEvent =
        Aws::CloudWatchLogs::Model::InputLogEvent().WithMessage(awsCwString).WithTimestamp(GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    this->logs.push_back(logEvent);

    if (this->logs.size() >= MAX_CLOUDWATCH_LOG_COUNT) {
        this->flush();
    }
}

VOID CloudwatchLogs::flush(BOOL sync)
{
    std::unique_lock<std::recursive_mutex> lock(this->sync.mutex);
    if (this->logs.size() == 0) {
        return;
    }

    auto pendingLogs = this->logs;
    this->logs.clear();

    // wait until previous logs have been flushed entirely
    auto waitUntilFlushed = [this] { return !this->sync.pending.load(); };
    this->sync.await.wait(lock, waitUntilFlushed);

    auto request = Aws::CloudWatchLogs::Model::PutLogEventsRequest()
                       .WithLogGroupName(this->pConfig->logGroupName.value)
                       .WithLogStreamName(this->pConfig->logStreamName.value)
                       .WithLogEvents(pendingLogs);

    if (this->token != "") {
        request.SetSequenceToken(this->token);
    }

    if (!sync) {
        auto asyncHandler = [this](const Aws::CloudWatchLogs::CloudWatchLogsClient* cwClientLog,
                                   const Aws::CloudWatchLogs::Model::PutLogEventsRequest& request,
                                   const Aws::CloudWatchLogs::Model::PutLogEventsOutcome& outcome,
                                   const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context) {
            UNUSED_PARAM(cwClientLog);
            UNUSED_PARAM(request);
            UNUSED_PARAM(context);

            if (!outcome.IsSuccess()) {
                // Need to use printf so that we don't get into an infinite loop where we keep flushing
                printf("Failed to push logs: %s\n", outcome.GetError().GetMessage().c_str());
            } else {
                DLOGS("Successfully pushed logs to cloudwatch");
                this->token = outcome.GetResult().GetNextSequenceToken();
            }

            this->sync.pending = FALSE;
            this->sync.await.notify_one();
        };

        this->sync.pending = TRUE;
        this->client.PutLogEventsAsync(request, asyncHandler);
    } else {
        auto outcome = this->client.PutLogEvents(request);
        if (!outcome.IsSuccess()) {
            // Need to use printf so that we don't get into an infinite loop where we keep flushing
            printf("Failed to push logs: %s\n", outcome.GetError().GetMessage().c_str());
        } else {
            DLOGS("Successfully pushed logs to cloudwatch");
            this->token = outcome.GetResult().GetNextSequenceToken();
        }
    }
}

} // namespace Canary
