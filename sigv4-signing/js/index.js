import {SignatureV4} from '@aws-sdk/signature-v4';
import {Sha256} from '@aws-crypto/sha256-js';
import {fromNodeProviderChain} from '@aws-sdk/credential-providers';
import {
    KinesisVideoClient,
    DescribeSignalingChannelCommand,
    GetSignalingChannelEndpointCommand
} from '@aws-sdk/client-kinesis-video';
import WebSocket from 'ws';

class WebSocketSigner {
    constructor(region, service = 'kinesisvideo') {
        this.signer = new SignatureV4({
            service,
            region,
            credentials: fromNodeProviderChain(),
            sha256: Sha256
        });
    }

    async getSignedURL(endpoint, queryParams = {}) {
        const url = new URL(endpoint);

        const request = {
            method: 'GET',
            hostname: url.hostname,
            path: url.pathname || '/',
            headers: {host: url.hostname},
            query: queryParams
        };

        const signedRequest = await this.signer.presign(request, {expiresIn: 299});

        const queryString = new URLSearchParams(signedRequest.query).toString();
        const signedUrl = `wss://${signedRequest.hostname}${signedRequest.path}?${queryString}`;
        return signedUrl;
    }
}

async function getChannelInfo(channelName, region, role) {
    const client = new KinesisVideoClient({requestTimeout: 5000, region: region});

    try {
        // Get channel ARN
        const describeCommand = new DescribeSignalingChannelCommand({
            ChannelName: channelName
        });
        const channelInfo = await client.send(describeCommand);
        const channelARN = channelInfo.ChannelInfo.ChannelARN;

        // Get signaling endpoint
        const endpointCommand = new GetSignalingChannelEndpointCommand({
            ChannelARN: channelARN,
            SingleMasterChannelEndpointConfiguration: {
                Protocols: ['WSS'],
                Role: role.toUpperCase(),
            },
        });
        const endpointInfo = await client.send(endpointCommand);
        const endpoint = endpointInfo.ResourceEndpointList[0].ResourceEndpoint;

        return {channelARN, endpoint};
    } catch (e) {
        console.error(`Error fetching signaling channel details: ${e}`);
    }
}

async function testWebSocketConnection(channelName, region, role = 'viewer', clientId = null) {
    try {
        console.log(`Getting info for channel: ${channelName}`);
        const {channelARN, endpoint} = await getChannelInfo(channelName, region, role);

        console.log(`Channel ARN: ${channelARN}`);
        console.log(`Endpoint: ${endpoint}`);

        const signer = new WebSocketSigner('us-west-2');

        const queryParams = {
            'X-Amz-ChannelARN': channelARN,
        };

        if (role === 'viewer') {
            const finalClientId = clientId || `test-client-${Date.now()}`;
            console.log(`Client ID: ${finalClientId}`);
            queryParams['X-Amz-ClientId'] = finalClientId;
        }

        const signedUrl = await signer.getSignedURL(endpoint, queryParams);
        console.log('Signed URL:', signedUrl);

        const ws = new WebSocket(signedUrl);

        ws.on('unexpected-response', (request, response) => {
            console.log('\n=== HTTP Error Response ===');
            console.log(`Status: ${response.statusCode} ${response.statusMessage}`);
            console.log('Headers:');
            Object.entries(response.headers).forEach(([key, value]) => {
                console.log(`  ${key}: ${value}`);
            });

            let body = '';
            response.on('data', (chunk) => {
                body += chunk;
            });
            response.on('end', () => {
                if (body) {
                    console.log('Response Body:', body);
                }
                console.log('==========================\n');
            });

            setTimeout(() => {
                ws.close();
                process.exit(1);
            }, 100);
        });

        ws.on('upgrade', (response) => {
            console.log('\n=== HTTP Response Headers ===');
            console.log(`Status: ${response.statusCode} ${response.statusMessage}`);
            Object.entries(response.headers).forEach(([key, value]) => {
                console.log(`${key}: ${value}`);
            });
            console.log('=============================\n');
        });

        ws.on('open', () => {
            console.log('WebSocket connected successfully!');

            // To verify that it's usable
            ws.ping('test-ping');
            console.log('Sent ping frame: test-ping');
        });

        ws.on('pong', (data) => {
            console.log('Received pong:', data.toString());
            ws.close();
        });

        ws.on('message', (data) => {
            console.log('Received message:', data.toString());
        });

        ws.on('error', (error) => {
            console.error('WebSocket error:', error.message);
        });

        ws.on('close', (code, reason) => {
            console.log(`WebSocket closed: ${code} ${reason}`);
        });

    } catch (error) {
        console.error('Error:', error.message);
    }
}

function main(args) {

    // args[0] = node
    // args[1] = index.js (this file)
    // args[2] = channelName
    // args[3] = role
    // args[4] = clientId
    if (args.length < 3) {
        console.log(`Usage: ${args[0]} ${args[1]} <channel-name> [role=master] [clientId='randStr']`);
        console.log(`Examples:`);
        console.log(`  ${args[0]} ${args[1]} demo-channel`);
        console.log(`  ${args[0]} ${args[1]} demo-channel master`);
        console.log(`  ${args[0]} ${args[1]} demo-channel viewer myclientId`);
        process.exit(1);
    }

    const channelName = args[2];

    const role = args[3] || 'viewer';
    if (role !== 'master' && role !== 'viewer') {
        console.error("[role] must be either 'master' or 'viewer'")
        process.exit(1);
    }

    let clientId = args[4] || null;
    if (role === 'master' && clientId) {
        console.warn('The specified clientId will not be used');
    }

    let region = process.env.AWS_DEFAULT_REGION;
    if (!region) {
        region = 'us-west-2';
    }
    console.log(`Using region: ${region}`);

    testWebSocketConnection(channelName, region, role, clientId);
}

main(process.argv)
