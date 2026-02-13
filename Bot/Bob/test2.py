from javascript import require, On

import threading
import time
import math

mineFlayer = require('mineflayer')

bot = mineFlayer.createBot({
    'host': 'localhost',
    'port': 27000,
    'username': 'Long'
})

@On(bot, 'login')
def handle_login(this):
    print("Bot logged in")

@On(bot, 'spawn')
def handle_spawn(this):
    print("Bot spawned")
    bot.setControlState('forward', True)


# ---- Move Exact Distance ----

def move_forward_blocks(blocks):
    start = bot.entity.position

    start_x = start.x
    start_z = start.z

    bot.setControlState('forward', True)

    while True:
        pos = bot.entity.position
        dx = pos.x - start_x
        dz = pos.z - start_z
        dist = math.sqrt(dx*dx + dz*dz)

        if dist >= blocks:
            break

        time.sleep(0.05)

    bot.setControlState('forward', False)
    print(f"Moved {blocks} blocks")

# ---- Command Parser ----

def control_loop():
    while True:
        cmd = input("Command (F<number>): ").strip().upper()

        if cmd.startswith('F'):
            try:
                blocks = float(cmd[1:])
                move_forward_blocks(blocks)
            except:
                print("Invalid format. Example: F10")

        elif cmd == "QUIT":
            break

threading.Thread(target=control_loop, daemon=True).start()

while True:
    time.sleep(1)