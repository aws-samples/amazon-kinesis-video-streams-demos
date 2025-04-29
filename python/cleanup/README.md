# Kinesis Video Cleanup Script

A Python utility to clean up stale Kinesis Video Streams and Signaling Channels used in automated testing environments.
This script identifies resources based on name regex patterns and creation time, deleting those older than a specified age.

## Setup

This script was developed and tested against Python 3.12.
Ensure you have Python 3.12 installed, then install dependencies via the provided `requirements.txt`:

```shell
pip3 install -r requirements.txt
```

## Command-line Options

All of these are optional.

| Flag                    | Description                                                    | Default                                     |
|:------------------------|:---------------------------------------------------------------|:--------------------------------------------|
| `-d`, `--dry-run`       | Lists resources that *would be* deleted, without deleting them | N/A                                         |
| `--stream-regex [val]`  | Regex pattern to match stream names                            | `^WrtcIngestionTestStream_[0-9a-fA-F]{32}$` |
| `--channel-regex [val]` | Regex pattern to match signaling channel names                 | `^ScaryTestChannel_[0-9a-zA-Z._-]{16}$`     |
| `--age-hours [val]`     | Delete resources older than this many hours                    | 24                                          |

## Examples

### Dry Run (Preview only)

```shell
python3 ./kvs_cleanup.py --dry-run
```
