<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 7: Install a Local Model

This procedure puts a chat model on the phone, so chat works offline and costs nothing per message.

**Before you start:** connect to Wi-Fi and plug in the charger. The suggested model is 2.4 GB. A phone with 8 GB of memory or more works best.

**Cost:** free. The model comes from Hugging Face, and no key is needed.

## Steps

1. On a new install, the empty Chat screen asks **Where should Chat run?** Tap **On this phone** and skip to step 4.
2. Otherwise, tap the gear to open Settings, and keep **Chat** selected.
3. Under **Chat runs**, tap **On this phone**, then **Manage models**.
4. On the first card, **Qwen3 4B Instruct, abliterated**, tap **Download (2.4 GB)**. The bar shows the progress, and the screen stays awake. On Wi-Fi the download takes about five minutes.
5. Wait for **Checking the file**. The app compares the file with the checksum Hugging Face publishes, which takes about ten seconds. The card then shows **In use**.
6. Go back to the main screen. The setup card names the model and says **Loads with your first message**.
7. Write a message and tap send. The first message loads the model into memory, which takes a few seconds. Later replies start in about a second.

## Tips

- **Pause** stops a download and keeps the part already on the phone. **Resume** continues from there.
- The line under each reply shows how fast the phone wrote it. The suggested model writes about 7 to 11 tokens per second on a Pixel 10 Pro XL.
- Settings, under Chat, also offers the **context size**. A larger context remembers more of a long conversation and uses more memory.
- To add a model that is not in the list, open its Files page on Hugging Face, copy the link to one `.gguf` file, and paste it under **Add a model from Hugging Face**.
- **Delete** on a card frees its space. The model can be downloaded again later.
