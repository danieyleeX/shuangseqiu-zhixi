# 官方数据同步

## 来源

- 官方页面：中国福利彩票发行管理中心双色球开奖查询
- API：`https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice`
- 参数：`name=ssq&pageNo=1&pageSize=100&systemType=PC`

Android 客户端只请求最新 100 期，不提供批量爬取。每条记录验证期号格式、红球数量/范围/唯一性、蓝球范围；成功后以期号为主键写入本地 SQLite。失败时保留现存数据。

## 随包历史数据

`app/src/main/assets/ssq_history.json.gz` 于 2026-11-08 通过官方公开查询接口分页获取。包含 2,085 期，时间范围为 2013-01-01 至 2026-11-05，足以支持近 10 年分析。号码按官方返回记录保存，不包含奖项或销售字段。

## 同步策略

- 启动时：仅在本地数据库为空时导入随包记录。
- 手动：历史页/首页点击“立即更新”。
- 后台：WorkManager 要求联网，每 12 小时执行一次；网络不可用或接口报错时使用重试，不覆盖已有记录。

