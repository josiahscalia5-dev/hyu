"""Thin wrapper around the Big-LaMa TorchScript model used to inpaint the reference.

Model file: https://github.com/enesmsahin/simple-lama-inpainting/releases/download/v0.1.0/big-lama.pt
Set LAMA_MODEL to its path (defaults to /tmp/big-lama.pt).
"""
import os

import numpy as np
import torch

_model = None


def _load():
    global _model
    if _model is None:
        path = os.environ.get("LAMA_MODEL", "/tmp/big-lama.pt")
        _model = torch.jit.load(path, map_location="cpu").eval()
    return _model


def inpaint(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """rgb: HxWx3 uint8, mask: HxW bool/uint8 (nonzero = fill). Returns HxWx3 uint8."""
    h, w = mask.shape
    ph, pw = (8 - h % 8) % 8, (8 - w % 8) % 8
    img = np.pad(rgb, ((0, ph), (0, pw), (0, 0)), mode="reflect").astype(np.float32) / 255.0
    m = np.pad((mask > 0).astype(np.float32), ((0, ph), (0, pw)), mode="reflect")
    img_t = torch.from_numpy(img).permute(2, 0, 1)[None]
    m_t = torch.from_numpy(m)[None, None]
    with torch.inference_mode():
        out = _load()(img_t, m_t)
    out = out[0].permute(1, 2, 0).numpy()[:h, :w]
    out = np.clip(out * 255.0 + 0.5, 0, 255).astype(np.uint8)
    # Keep original pixels outside the mask bit-exact.
    keep = mask == 0
    out[keep] = rgb[keep]
    return out
