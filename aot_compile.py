#!/usr/bin/env python3
"""
LiteRT NPU AOT 编译脚本

依赖安装：
  pip install "ai-edge-litert>=2.1.4" "ai-edge-litert-sdk-qualcomm>=0.2.0"

用法：
  # 编译 MODELS 列表中的所有模型
  python aot_compile.py

  # 指定 SoC（仅编译该 SoC）
  python aot_compile.py --soc SM8650

  # 指定模型文件
  python aot_compile.py --model path/to/model.tflite --soc SM8650
"""

import argparse
import sys
from pathlib import Path

# ======================== 配置区域 ========================

SCRIPT_DIR = Path(__file__).resolve().parent
TFLITE_BASE_DIR = SCRIPT_DIR / "app" / "src" / "main" / "assets" / "tflite"

# 待编译模型列表（相对于 TFLITE_BASE_DIR 的路径）
# 替换为你自己的模型文件，例如：
#   "detection/tflite/yolo26n/models/n/yolo26n_float16-no-nms.tflite"
MODELS = [
    # "yolo26/models/s/yolo26s_float16-no-nms.tflite",
    # "yolo26/models/s/yolo26s_float32-no-nms.tflite",
    # "yolo26/models/s/yolo26s_int8-no-nms.tflite",
]

AOT_OUTPUT_SUBDIR = "aot"

TARGET_SCS = {
    "SM8450": "Snapdragon 8 Gen 1",
    "SM8550": "Snapdragon 8 Gen 2",
    "SM8650": "Snapdragon 8 Gen 3",
    "SM8750": "Snapdragon 8 Elite",
    "SM8850": "Snapdragon 8 Elite Gen 5",
}

KEEP_GOING = True

# ======================== 编译逻辑 ========================

def check_dependencies():
    """检查必要的 Python 包是否已安装"""
    missing = []
    try:
        from ai_edge_litert.aot import aot_compile
    except ImportError:
        missing.append("ai-edge-litert>=2.1.4")

    try:
        from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target
    except ImportError:
        missing.append("ai-edge-litert-sdk-qualcomm>=0.2.0")

    if missing:
        print("缺少依赖包，请先安装：")
        for pkg in missing:
            print(f"  pip install {pkg}")
        sys.exit(1)


def compile_model(relative_model_path: str, soc_filter: list[str] | None = None) -> bool:
    from ai_edge_litert.aot import aot_compile as aot_lib
    from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target

    abs_model_path = TFLITE_BASE_DIR / relative_model_path
    if not abs_model_path.exists():
        print(f"  [错误] 模型文件不存在: {abs_model_path}")
        return False

    model_name = Path(relative_model_path).stem
    model_dir = Path(relative_model_path).parent
    output_dir = TFLITE_BASE_DIR / model_dir / AOT_OUTPUT_SUBDIR
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n{'='*60}")
    print(f"编译模型: {model_name}")
    print(f"输出目录: {output_dir}")
    print(f"{'='*60}")

    # 构建目标列表
    soc_model_map = {
        "SM8450": qnn_target.SocModel.SM8450,
        "SM8550": qnn_target.SocModel.SM8550,
        "SM8650": qnn_target.SocModel.SM8650,
        "SM8750": qnn_target.SocModel.SM8750,
        "SM8850": qnn_target.SocModel.SM8850,
    }
    soc_list = soc_filter if soc_filter else list(TARGET_SCS.keys())
    targets = [qnn_target.Target(soc_model_map[soc]) for soc in soc_list if soc in soc_model_map]

    if not targets:
        print(f"  [错误] 没有匹配的 SoC 目标")
        return False

    try:
        compiled_models = aot_lib.aot_compile(
            str(abs_model_path),
            target=targets,
            keep_going=KEEP_GOING,
        )

        print(compiled_models.compilation_report())
        compiled_models.export(str(output_dir), model_name=model_name)

        # 列出生成的文件
        for f in sorted(output_dir.glob(f"{model_name}_*.tflite")):
            size_mb = f.stat().st_size / (1024 * 1024)
            print(f"  ✅ {f.name} ({size_mb:.1f} MB)")

        return True

    except Exception as e:
        print(f"  ❌ 编译失败: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="LiteRT NPU AOT 编译工具")
    parser.add_argument("--soc", action="append", dest="socs",
                        help="目标 SoC（可多次指定），如 --soc SM8650 --soc SM8750")
    parser.add_argument("--model", action="append", dest="models",
                        help="模型文件路径（相对于 assets/tflite/，可多次指定），"
                             "如 --model detection/tflite/yolo26n/models/n/yolo26n_float16-no-nms.tflite")
    args = parser.parse_args()

    # 确定要编译的模型列表
    model_list = args.models if args.models else MODELS
    if not model_list:
        print("错误：没有指定要编译的模型。请配置 MODELS 列表或使用 --model 参数。")
        print("示例：python aot_compile.py --model detection/tflite/yolo26n/models/n/yolo26n_float16-no-nms.tflite")
        sys.exit(1)

    # 校验 --soc 参数
    soc_filter = args.socs if args.socs else None
    if soc_filter:
        invalid = [s for s in soc_filter if s not in TARGET_SCS]
        if invalid:
            print(f"错误：不支持的 SoC: {', '.join(invalid)}")
            print(f"支持的 SoC: {', '.join(TARGET_SCS.keys())}")
            sys.exit(1)

    print("LiteRT NPU AOT 编译工具")
    print(f"模型目录: {TFLITE_BASE_DIR}")
    if soc_filter:
        print(f"目标 SoC: {', '.join(soc_filter)}")
    print(f"编译模型数: {len(model_list)}\n")

    check_dependencies()

    results = {}
    for model_path in model_list:
        results[model_path] = compile_model(model_path, soc_filter=soc_filter)

    print(f"\n{'='*60}")
    success_count = sum(results.values())
    fail_count = len(results) - success_count
    print(f"成功: {success_count} / 失败: {fail_count}")

    if fail_count == 0:
        print("\n✅ 所有模型编译成功！")
    else:
        sys.exit(1)


if __name__ == "__main__":
    main()
