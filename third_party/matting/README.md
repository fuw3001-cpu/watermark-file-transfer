# 本地抠图

- 模型：U²-Net 的轻量 U2NETP，原项目 https://github.com/xuebinqin/U-2-Net ，Apache-2.0。
- 随包 ONNX 权重由 https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx 分发；4,574,861 字节，SHA-256 `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`。
- 运行库：`com.microsoft.onnxruntime:onnxruntime-android:1.23.2`，MIT，源代码 https://github.com/microsoft/onnxruntime/tree/v1.23.2 。
- 完整模型许可、运行库许可和第三方声明保存在本目录，并打包到 App「开源许可」。
- 模型由 tools/prepare_documents.py 校验并作为资源随包提供，手机上不另行下载，不调用云端服务。
- 模板为本项目使用 Android Canvas 绘制的原创几何图形；自定义模板由用户从系统文件选择器导入。
