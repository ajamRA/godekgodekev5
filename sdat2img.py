#!/usr/bin/env python
# sdat2img.py - Convert Android system.new.dat to ext4 system.img

import sys
import os

def rangeset(src):
    src_set = [int(item) for item in src.split(',')]
    num_set = src_set[0]
    index = 1
    out_set = []
    while num_set > 0:
        out_set.append((src_set[index], src_set[index+1]))
        index += 2
        num_set -= 2
    return out_set

def parse_transfer_list_file(path):
    trans_list = open(path, 'r')
    version = int(trans_list.readline())
    new_blocks = int(trans_list.readline())

    if version >= 2:
        trans_list.readline()
        trans_list.readline()

    commands = []
    for line in trans_list:
        line = line.split(' ')
        cmd = line[0]
        if cmd in ['erase', 'new', 'zero']:
            commands.append([cmd, rangeset(line[1].strip())])
        else:
            # skip unknown commands
            pass
    trans_list.close()
    return version, new_blocks, commands

def main(TRANSFER_LIST_FILE, NEW_DATA_FILE, OUTPUT_IMAGE_FILE):
    BLOCK_SIZE = 4096
    version, new_blocks, commands = parse_transfer_list_file(TRANSFER_LIST_FILE)

    if os.path.exists(OUTPUT_IMAGE_FILE):
        os.remove(OUTPUT_IMAGE_FILE)

    output_img = open(OUTPUT_IMAGE_FILE, 'wb')
    new_data_file = open(NEW_DATA_FILE, 'rb')

    print(f"Converting sdat to img (max blocks: {new_blocks}) ...")

    all_block_pairs = []
    for cmd in commands:
        all_block_pairs.extend(cmd[1])
    max_file_size = max(pair[1] for pair in all_block_pairs) * BLOCK_SIZE

    output_img.truncate(max_file_size)

    for command in commands:
        if command[0] == 'new':
            for block in command[1]:
                begin = block[0]
                end = block[1]
                block_count = end - begin
                output_img.seek(begin * BLOCK_SIZE)
                while block_count > 0:
                    read_blocks = min(block_count, 1024)
                    output_img.write(new_data_file.read(read_blocks * BLOCK_SIZE))
                    block_count -= read_blocks
        else:
            pass

    output_img.close()
    new_data_file.close()
    print("Done converting to system.img!")

if __name__ == '__main__':
    import sys
    if len(sys.argv) >= 4:
        t_list = sys.argv[1]
        n_data = sys.argv[2]
        o_img = sys.argv[3]
    else:
        t_list = r"D:\apps\emas-ota\Geely Xingyuan\extracted_test\system.transfer.list"
        n_data = r"D:\apps\emas-ota\Geely Xingyuan\extracted_test\system.new.dat"
        o_img = r"D:\apps\emas-ota\Geely Xingyuan\extracted_test\system.img"
    main(t_list, n_data, o_img)

