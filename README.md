
<div align="center">
<img width="125" height="125" src="https://github.com/gedoor/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>  
  
Legado / 开源阅读
<br>
Legado is a free and open source novel reader for Android.
</div>

## legado-tts/阅读内置EdgeTTS 微软大声朗读
- app基于legado https://github.com/gedoor/legado ✅
- Edgetts基于rany2/edge-tts https://github.com/rany2/edge-tts ✅
- 豆包tts基于 https://github.com/callmerio/doubao-tts  ✅( 豆包格局不多)

### 豆包TTS 测试
- 2025-01-01新增豆包TTS,  需要先添加 cookie 首次请求比较慢耐心等待, 其次不支持调语速,避免请求频繁拦截, 朗读的进度和语音可能有错位
```bash
  # cookie获取参考   
  # 打开浏览器访问 豆包 并登录账号
  # 按 F12 打开开发者工具
  # 切换到 Application 标签页
  # 左侧展开 Cookies → 点击 https://www.doubao.com
  # 找到以下三个必需字段并复制其值：

 sessionid=你的sessionid; sid_guard=你的sid_guard; uid_tt=你的uid_tt 
 
 粘贴到豆包TTS 输入框内  登录后的 Cookie：约 30天, 到期可能需要重新更新 , 我简单试了下效果貌似还没Edge好, 仅供测试, 切勿频繁调用 
```

### 为什么会有这个仓库? 
- 我曾经提交过PR挂了几个星期也没人合并, So waht ever! 我=null, 我+GPT=无所不能
- 自己有这个需求,晚上不听几章睡不着 装了阅读再安装TTS有些多余,现有所谓TTS引擎本质上仍是rany2/edge-tts

### 书源
- 要么去喵公子导入, 要么本地
- 本地电子书推荐: https://github.com/BlankRain/ebooks

### 主要修改
- 修改音频流的暂存方式 (写硬盘=>写内存)
- 原作者来是把音频缓存硬盘上会频繁执行写入和删除(有多少段落就写多少次),
- 频繁执行写入影响寿命或许对于现代存储来说影响微乎其微😋 但是我改成了放在内存中, 每读完一章就释放已读完的的媒体, 修改内容参见PR:gedoor/legado#5304
- 跟随rany2/edge-tts EdgeVersion 143.0.3650.75
- 不定时合并(gedoor/legado)主仓更新最近一次是在 2026-05-08
![detail.png](https://raw.githubusercontent.com/WangSunio/img/main/images/pre.png)

### happy every day 😄 😄