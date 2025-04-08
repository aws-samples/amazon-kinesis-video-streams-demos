import argparse
import os
import pandas as pd
import numpy as np

def extract_metrics(log_file):
    offer_to_answer_times = []
    offer_to_connected_times = []
    offer_to_first_frame_times = []

    try:
        with open(log_file, 'r') as file:
            for line in file:
                line = line.lower()
                if "answer" in line:
                    if len(offer_to_answer_times) != len(offer_to_connected_times):
                        offer_to_connected_times.append(None)
                    if len(offer_to_answer_times) != len(offer_to_first_frame_times):
                        offer_to_first_frame_times.append(None)
                    offer_to_answer_time = float(line.split(": ")[1])
                    offer_to_answer_times.append(offer_to_answer_time)
                elif ("connected" in line or "peer" in line) and "failed" not in line:
                    offer_to_connected_time = float(line.split(": ")[1])
                    offer_to_connected_times.append(offer_to_connected_time)
                elif "frame" in line:
                    offer_to_first_frame_time = float(line.split(": ")[1])
                    offer_to_first_frame_times.append(offer_to_first_frame_time)
    except Exception as err:
        print(f'Error: {err} while reading the line in: {log_file}')
        print(f'Line: {line}')

    if len(offer_to_answer_times) != len(offer_to_connected_times):
        offer_to_connected_times.append(None)
    if len(offer_to_answer_times) != len(offer_to_first_frame_times):
        offer_to_first_frame_times.append(None)

    return offer_to_answer_times, offer_to_connected_times, offer_to_first_frame_times

def calculate_metrics(log_file, output_dir, output_format):
    raw_offer_to_answer_times, raw_offer_to_connected_times, raw_offer_to_first_frame_times = extract_metrics(log_file)

    # Remove rows that contain a None
    cleaned_data = [(a, b, c) for a, b, c in
                    zip(raw_offer_to_answer_times, raw_offer_to_connected_times, raw_offer_to_first_frame_times) if
                    None not in (a, b, c)]
    offer_to_answer_times, offer_to_connected_times, offer_to_first_frame_times = zip(*cleaned_data)

    data = {
        'Offer to Answer Time': offer_to_answer_times,
        'Offer to Connected Time': offer_to_connected_times,
        'Offer to First Frame Time': offer_to_first_frame_times
    }

    print(log_file)
    print(f'Sample size: {len(raw_offer_to_first_frame_times)}')
    print(f'Failing runs: {len(raw_offer_to_first_frame_times) - len(offer_to_first_frame_times)}')
    print(f'Failure rate: {round((1 - (len(offer_to_first_frame_times) / len(raw_offer_to_first_frame_times))) * 100, 2)}%')
    print(f"Failure breakdown:\n"
          f" {sum(1 for item in raw_offer_to_connected_times if item is None)} didn't establish a connection\n"
          f" {sum(1 for item in raw_offer_to_first_frame_times if item is None) - sum(1 for item in raw_offer_to_connected_times if item is None)} didn't playback anything")

    df = pd.DataFrame(data)

    # Save to file
    output_file = f'{output_dir}/{os.path.basename(log_file).removesuffix(".txt")}-extracted'
    if output_format == 'csv':
        df.to_csv(f'{output_file}.csv', index=False)
    elif output_format == 'tsv':
        df.to_csv(f'{output_file}.tsv', index=False, sep='\t')

    ota = {
        'p50': np.percentile(offer_to_answer_times, 50),
        'p90': np.percentile(offer_to_answer_times, 90),
        'Average': np.mean(offer_to_answer_times),
        'Min': np.min(offer_to_answer_times),
        'Max': np.max(offer_to_answer_times)
    }

    otpcc = {
        'p50': np.percentile(offer_to_connected_times, 50),
        'p90': np.percentile(offer_to_connected_times, 90),
        'Average': np.mean(offer_to_connected_times),
        'Min': np.min(offer_to_connected_times),
        'Max': np.max(offer_to_connected_times)
    }

    otff = {
        'p50': np.percentile(offer_to_first_frame_times, 50),
        'p90': np.percentile(offer_to_first_frame_times, 90),
        'Average': np.mean(offer_to_first_frame_times),
        'Min': np.min(offer_to_first_frame_times),
        'Max': np.max(offer_to_first_frame_times)
    }

    df2 = pd.DataFrame([ota, otpcc, otff], index=['Offer to Answer', 'Offer to Peer Connection Connected', 'Offer to First Frame'])

    # Save to file
    output_file = f'{output_dir}/{os.path.basename(log_file).removesuffix(".txt")}-aggregated'
    if output_format == 'csv':
        df2.to_csv(f'{output_file}.csv', index=False)
    elif output_format == 'tsv':
        df2.to_csv(f'{output_file}.tsv', index=False, sep='\t')

    return df, df2

def read_log_files(directory):
    try:
        file_names = [f for f in os.listdir(directory) if os.path.isfile(os.path.join(directory, f)) and not f.startswith('.')]
        return file_names
    except Exception as e:
        print(f"An error occurred: {e}")
        return []

# Written for Python 3.12
def main():
    parser = argparse.ArgumentParser(description='Log file metrics analyzer and aggregator')
    parser.add_argument('-i', '--input', default='logs', help='Input directory (default: logs)')
    parser.add_argument('-o', '--output', default='output', help='Output directory (default: output)')
    parser.add_argument('-f', '--format', choices=['csv', 'tsv'], default='tsv', help='Output file format (default: tsv)')
    args = parser.parse_args()

    input_dir = args.input
    output_dir = args.output
    output_format = args.format

    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    log_files = [os.path.join(input_dir, f) for f in read_log_files(input_dir)]

    for log_file in log_files:
        print('--------------------------------')
        df, df2 = calculate_metrics(log_file, output_dir, output_format)
        print(df)
        print(df2)

if __name__ == '__main__':
    main()
