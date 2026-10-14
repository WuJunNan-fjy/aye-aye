# 来源与第三方许可

aye-aye 自有代码采用根目录 `LICENSE` 中的 MIT 许可证。第三方内容仍受各自许可证约束。

## DeepSeek Harness

- 来源：https://github.com/deepseek-ai/deepseek-harness
- 上游许可证：https://github.com/deepseek-ai/deepseek-harness/blob/master/LICENSE
- 版权：Copyright (c) 2026 DeepSeek
- 许可：MIT
- 本项目涉及范围：前端界面、交互及样式体系的参考与改造，主要位于 `apps/aye-frontend/`。本项目使用独立的 Vite / React / Zustand 前端和 Java 服务端，不依赖上游的 Cordis 运行时。
- 维护者回忆在上游刚开源后的数天内进行了参考，具体版本和提交已无法确认。此处保留上游许可，不把这些内容声明为完全原创；实际复用文件范围仍需进一步核对。

上述来源声明不表示 DeepSeek 对 aye-aye 提供支持或背书。应用商标和产品名称不因 MIT 许可而自动授权。

```text
MIT License

Copyright (c) 2026 DeepSeek

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## DeepWork

维护者已确认 DeepWork 为其自有项目。aye-ai-core 中与 DeepWork 对应的注释用于说明自有实现的演进关系。

## 依赖及分发资源

Maven、npm 与 pnpm 依赖保留各自许可。上述声明不是完整的传递依赖清单，也不能替代安装包中的第三方许可文件。

Windows 安装包包含 Electron、Java 运行时及后端依赖；发布验收时需要检查随包许可文件，尤其是裁剪 Java 运行时的 `legal/` 目录。
