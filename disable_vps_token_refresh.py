import os

server_py_path = '/home/ubuntu/emas5/server.py'
tokens_json_path = '/home/ubuntu/emas5/emas_tokens.json'

with open(server_py_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_lines = []
in_func = False
patched = False

for line in lines:
    if line.startswith('async def refresh_token_if_needed():'):
        in_func = True
        patched = True
        new_lines.append(line)
        new_lines.append('    # [OFFICIAL PROTON APP PROTECTION]\n')
        new_lines.append('    # Auto-refresh disabled to prevent kicking user out of the official Proton e.MAS mobile app.\n')
        new_lines.append('    return None\n')
        continue
    
    if in_func:
        # Check if we exited the function (new top-level def, @, or class)
        if line.startswith('def ') or line.startswith('async def ') or line.startswith('@') or line.startswith('class '):
            in_func = False
            new_lines.append(line)
        else:
            # Skip old function body
            continue
    else:
        new_lines.append(line)

if patched:
    with open(server_py_path, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    print("SUCCESS: refresh_token_if_needed completely neutralized!")
else:
    print("WARNING: Function not found!")

# Clear emas_tokens.json so no stale/active tokens are stored
with open(tokens_json_path, 'w', encoding='utf-8') as f:
    f.write('{}\n')
print("SUCCESS: emas_tokens.json cleared!")
