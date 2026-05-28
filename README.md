
# tts语音引擎

## ddo-tts/内置EdgeTTS 微软大声朗读
- Edgetts基于rany2/edge-tts https://github.com/rany2/edge-tts ✅


### 为什么会有这个仓库? 
- 我曾经提交过PR挂了几个星期也没人合并, So waht ever! 我=null, 我+GPT=无所不能
- 自己有这个需求,晚上不听几章睡不着 再安装TTS有些多余,现有所谓TTS引擎本质上仍是rany2/edge-tts
- 本地电子书推荐: https://github.com/BlankRain/ebooks

### 主要修改
- 修改音频流的暂存方式 (写硬盘=>写内存)
- 原作者来是把音频缓存硬盘上会频繁执行写入和删除(有多少段落就写多少次),
- 频繁执行写入影响寿命或许对于现代存储来说影响微乎其微😋 但是我改成了放在内存中, 每读完一章就释放已读完的的媒体, 
- 跟随rany2/edge-tts EdgeVersion 143.0.3650.75

~~不定时合并主仓更新最近一次是在 2026-05-08。~~ 主仓已经删库跑路了, 估计是进去了在座的各位都有责任, 进去有吃有喝，踩缝纫机还有工资拿


![detail.png](https://raw.githubusercontent.com/WangSunio/img/main/images/pre.png)

### happy every day 😄 😄
