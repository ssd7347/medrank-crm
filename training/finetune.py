"""Fine-tunes our own voice-agent model with LoRA. Needs an NVIDIA GPU: run it on Google Colab's free T4.

In Colab (Runtime -> Change runtime type -> T4 GPU), upload train.jsonl, val.jsonl and this file, then run:

    !pip install unsloth
    !python finetune.py

About 30-60 minutes for ~1,500 calls. The result is out/ourvoice-gguf/*.gguf (about 2.5 GB): download it and load it
into Ollama with make_modelfile.py (see README.md).

The base model must allow commercial use, because the CRM is sold: Qwen3-4B-Instruct-2507 is Apache 2.0.
(Qwen2.5-3B is NOT: its licence is research and non-commercial only.)
"""

import argparse
import inspect
import json
import os
import sys

# Unsloth must be imported before transformers and trl.
from unsloth import FastLanguageModel  # noqa: I001
from unsloth.chat_templates import train_on_responses_only

import torch
from datasets import Dataset
from trl import SFTConfig, SFTTrainer


def load(path: str, tok, max_len: int) -> Dataset:
    """Each call becomes one text in the model's own chat format. Calls longer than max_len tokens are left out."""
    texts, skipped = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            text = tok.apply_chat_template(row["messages"], tools=row["tools"], tokenize=False)
            if len(tok(text, add_special_tokens=False)["input_ids"]) > max_len:
                skipped += 1
                continue
            texts.append({"text": text})
    print(f"{path}: {len(texts)} calls" + (f" ({skipped} too long, left out)" if skipped else ""))
    # Built from plain strings: nested tool arguments would otherwise be forced into one shared schema.
    return Dataset.from_list(texts)


def only_supported(cls, **kwargs):
    """trl renames arguments between versions; pass only the ones this version knows."""
    known = set(inspect.signature(cls.__init__).parameters)
    return {k: v for k, v in kwargs.items() if k in known}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default=os.environ.get("BASE_MODEL", "unsloth/Qwen3-4B-Instruct-2507"))
    ap.add_argument("--train", default="train.jsonl")
    ap.add_argument("--val", default="val.jsonl")
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--max-len", type=int, default=4096)
    ap.add_argument("--rank", type=int, default=16, help="LoRA rank: higher learns more but needs more memory")
    ap.add_argument("--out", default="out")
    ap.add_argument("--quant", default="q4_k_m", help="GGUF quantisation for Ollama (q4_k_m is small and fast)")
    a = ap.parse_args()

    if not torch.cuda.is_available():
        sys.exit("No NVIDIA GPU found. Run this on Google Colab with a T4 GPU (Runtime -> Change runtime type).")

    model, tok = FastLanguageModel.from_pretrained(a.base, max_seq_length=a.max_len, load_in_4bit=True)
    model = FastLanguageModel.get_peft_model(
        model, r=a.rank, lora_alpha=a.rank * 2, lora_dropout=0, bias="none", random_state=3407,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        use_gradient_checkpointing="unsloth")

    train, val = load(a.train, tok, a.max_len), load(a.val, tok, a.max_len)
    bf16 = torch.cuda.is_bf16_supported()
    cfg = SFTConfig(**only_supported(
        SFTConfig, output_dir=f"{a.out}/checkpoints", dataset_text_field="text", max_seq_length=a.max_len,
        max_length=a.max_len, packing=False, per_device_train_batch_size=2, gradient_accumulation_steps=8,
        num_train_epochs=a.epochs, learning_rate=2e-4, lr_scheduler_type="cosine", warmup_ratio=0.03,
        weight_decay=0.01, optim="adamw_8bit", bf16=bf16, fp16=not bf16, logging_steps=10, eval_strategy="steps",
        eval_steps=50, save_strategy="no", report_to="none", seed=3407))
    trainer = SFTTrainer(**only_supported(SFTTrainer, model=model, processing_class=tok, tokenizer=tok,
                                          train_dataset=train, eval_dataset=val, args=cfg))
    # Learn only the agent's turns (replies and tool calls); the script, caller and tool results are context.
    trainer = train_on_responses_only(trainer, instruction_part="<|im_start|>user\n",
                                      response_part="<|im_start|>assistant\n")
    stats = trainer.train()
    print(f"Training done in {stats.metrics.get('train_runtime', 0) / 60:.0f} min; final loss {stats.metrics.get('train_loss', 0):.3f}")
    print("Validation:", trainer.evaluate())

    # A quick look: the trained model continues one validation call.
    FastLanguageModel.for_inference(model)
    with open(a.val, encoding="utf-8") as f:
        row = json.loads(f.readline())
    upto = next(i for i, m in enumerate(row["messages"]) if i > 3 and m["role"] == "assistant")
    prompt = tok.apply_chat_template(row["messages"][:upto], tools=row["tools"], tokenize=False, add_generation_prompt=True)
    ids = tok(prompt, return_tensors="pt").to("cuda")
    out = model.generate(**ids, max_new_tokens=120, do_sample=False)
    print("\nCaller said:", row["messages"][upto - 1]["content"][:200])
    print("Our model replies:", tok.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True))

    model.save_pretrained(f"{a.out}/ourvoice-lora")
    tok.save_pretrained(f"{a.out}/ourvoice-lora")
    model.save_pretrained_gguf(f"{a.out}/ourvoice-gguf", tok, quantization_method=a.quant)
    gguf = [f for f in os.listdir(f"{a.out}/ourvoice-gguf") if f.endswith(".gguf")] if os.path.isdir(f"{a.out}/ourvoice-gguf") else []
    print("\nSaved:", f"{a.out}/ourvoice-lora", *[f"{a.out}/ourvoice-gguf/{g}" for g in gguf], sep="\n  ")
    print("Download the .gguf file and follow README.md, 'Use the trained model'.")


if __name__ == "__main__":
    main()
