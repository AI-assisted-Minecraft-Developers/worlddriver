# TODO

> 镜像 Task 跟踪器的长期工作。重要根因写进 memory(reference/project)。

## 2026-07-02 ⭐当前优先级(用户硬指令)与任务镜像(Task #2-#7)

**优先级指令**:①高效发现问题与验证(观测/验证基建)最优先 ②本项目代码防腐/架构优化次之 ③发现并解决具体寻路问题最后。

- **P1 观测基建(Task #2,in_progress)**:11 类 [expect] 执行器预期报警已 committed(DIG-dropped/DIG-slow/JUMP-noRise/MOVE-noMove/REPATH-flip/DRIVE-tear/ADVANCE-deadzone/GEAR-degraded + 本日新增 CLUTCH-noArm/PLACE-noBlock/IDLE-drowning)+ JUMP 阈值 0.8 校准 + forensic.py 同 tick 配对验尸 + 反射化 setting(新 flag 零接线)+ 验收协议 preflight/expect 计数/churn-drown guard。**剩:重启客户端激活(遇 GL hang,复现 2026-06-27 签名)+ smoke。**
- **P2 防腐(Task #3)**:验收 FLAGS(~27 个已验证 default-OFF flag)与 accept_cycle.py 从 scratchpad 入库。
- **P3 寻路 lane(Task #4-#6)**:①escape-vs-dig 调度冲突(C24-J3 DIG-dropped 归因=DrowningEscape 抢占断 breakHold)②dig aim-drift(DIG-slow 归因)③repath 振荡 hysteresis 正确场景重 A/B((-252,70,209),K≥6)+ mount 双稳态(replay-0016)。
- **终门(Task #7,blocked by #2)**:#47 三周期验收(3×随机长途+每程 replay×3 全绿);已 24 cycle 未连三绿,J1 已稳。flip-default 待用户。

## 2026-06-27 ⭐弧长追踪执行器重构(治本)+ 打破一个月 0-commit 死锁

用户硬批评「一个月点修无进展、向后跳/挖墙/卡浮萍仍在、每次只修一小段发现不好就 revert、原地踏步」后,转向**结构性重构**:把执行器的「每-tick 瞬时几何门 + bob-immune 计数器动物园」替换成**弧长追踪 pure-pursuit**(脚投影到 path 折线→单调弧长 s→切线驱动)。分阶段、各自 flag、全 **live/replay 验证**(非 arena-green):

- **P1 `walkerArcLengthAdvance`(default ON)**:用投影段推进 step 指针,旁路 7 个 bob 补偿门。LIVE:`projSeg>step` 30→4(step-freeze/卡浮萍消除)。
- **P2 `walkerTangentAim`(default ON)**:相机+身体瞄 s+lookahead 处路径切线(永不 180° 翻),消除**向后跳/反复横跳/贴墙**。LIVE:平滑段 `dYaw≥120` 123→3(0.2%)。含 **dry step-up 例外**(ascent 节点用 legacy 节点 aim 让 pivotForStepUp/stepUpJump 对齐 mount)→修**上坡跳不上方块**(LIVE bot 爬升 y62→y94 解锁整座山)。
- **P3 `walkerArcLengthWedge`(default ON)**:bob/jitter-免疫 ram-wedge recovery(`|ds|<0.05 + horizontalCollision` 累积 30t→折进 proven fellOffPath blacklist+reroute),替代挂 noStepProgressTicks 被 ram-jitter 清零的 descentRamStuck 族。**EXCLUDES water**(水域 climb-out 有专属 recovery)。LIVE:water-exclude 0 误触发。
- `walkerArcLengthShadow`(default OFF,log-only 诊断)。`PathProjection.java` = 抽出的可读投影核心(巨兽分解第一步)。

**净效果**:干地 traversal 根治了用户点名的向后跳/横跳/贴墙/上坡跳不上;`HEAD f9a4a2e`(一个月前)以来的全部累积工作落定为 committed baseline。

### ⬜⬜ 已知剩余 dominant blocker:水域 climb-out / bank-dig(near-fundamental,下一专门周期)
arc-length 相位只治**干地**;**水域 jank 是独立大域、未治** = 用户点名的「挖墙」+「在水里卡住」。**三器定层(-658,61 live)**:① planner **完全干净**(debug.plan chain reached/0 backtrack,routes 平缓爬升出水);② 纯**执行层**:submerged bot(`deepDig=true`)在 +3 岸脚被抑制 pillar 爬出(2461 要 `!deepDig`)→落到 block-less bank dig(2606 deepDig 分支)→水下原地挖高岸,而非先浮出水面/沿 path surface-swim 到平缓出水点。**修向**:水域 climb-out 不在 path 仍沿水时过早 engage 本地陡岸;submerged 先 surface 再 climb。浮力 bot 放 foothold 站不稳是 near-fundamental(dig-over-pillar 是当前 least-bad)。Task #59。详见 memory [[project_arclength_pursuit_refactor]]。

## P0 — 寻路丝滑(长途随机目标一路丝滑、不停顿、不跳变视角;验收=pathChart + 视频转录双通道无异常)

镜像 Task #29(回归 goal)+ #34(规划层深水可站性)。

### ⏳ 2026-06-24/25 执行器 fix 族 I/J/K/L/M(uncommitted,各 GT 50/50 + live replay A/B 验证)— 陡山爬升丝滑
真根因全在 Walker/BotInteract 执行器层(非规划层),逐 tick telemetry + replay A/B + GT 三器定层:
- **fix K**:survival pillar-recovery `BotInteract.ensureHoldingPillarBlock` 只扫 hotbar(inventory 分支 creative-only)→survival bot cobble 在 inv slot 9 取不到→slide-back 恢复静默 no-op = 主 churn。修=survival inv→hotbar SWAP(镜像 AutoEquip)。drift-stalls 28→2。
- **fix L**:对角爬升 inter-step sprint→momentum 横向漂离窄楼梯线→slide→ascentRamSlide pillar-spam。修=`diagAscent`(对角干地爬升)drop sprint。pillarUp 292→0、cobble 用 56→2、到达 2× 快。
- **fix M**:tree-canopy/dirt overhang 下 pillar-recovery 撞顶 bob 不升 247t(fix K 副作用)。修=no-rise give-up(`PILLAR_NORISE_GIVEUP=50`t 不升→gate fellBelowRoute→foot-search re-route 绕开)。trap 247→0、pillarUp 339→95。
- **fix I/J**:深坑 pillar-thrash 105s→4s;慢游 approach-sprint +78%。
- **残:vine-over-water climb-out**(#50,-711,67 vine-wall-over-pocket)+ open-water surface-swim drift(#49)。**2026-06-25 三修全 revert→working tree + live client 均 clean I-N'**:fix O(planner floating cost-tax→A* 撑爆 30000 node-cap)、fix P(executor 删 vine `!isInWater`→-711 churn 1086t/54s ~9× WORSE)、fix Q(Plan-agent spec:Part2 parkour-landed-on-vine handoff `onVine 在 parkour edge 放行 when landedOnVine`,GT 50/50)。⭐**fix Q 真 blocker = VALIDATION 不是 design**:`replan:true` journey replay 每跑 path 不同→几乎不复现 parkour-LANDS-on-vine target(replay#3 没碰 vine/#4 off-path drift),pre-widen 命中时 grab 但 **bob-stuck 爬不上**(unknown#3=vine yaw 瞄 overhead node 非 wall)→无法干净 A/B→revert。**真路(fresh clear-headed session):先建 DETERMINISTIC `vineOverWaterClimbArena`(spec 全文 scratchpad/vine-over-water-fixspec.md:layout+never-pocket-entry predicate,⚠先验 fake-player vine-cling fidelity)做 reproducible A/B——journey replay 对此 genre 根本不够;再 Part2+widen+unknown#3 ascent-yaw 修**。⚠~450-turn fatigue 下 O/P/Q 三连 revert=别再盲改 water/vine,需清醒 session。**#47 因 -711 genre 未根治、round3 未过**。详见 memory [[reference_water_brake_sneak_sink_and_parkour_yaw_fix]]。
  - **2026-06-25 续(突破+第4次 live 破)**:✅DETERMINISTIC `vineOverWaterClimbArena`+`vineClingFidelityProbe` 已建(validation-tooling blocker 解,byte-identical 复现 baseline)。agent 实现 **Part A `vineWallYaw`**(Walker:738,climb 时 yaw 瞄 vine 背墙=持续 into-wall press=vanilla climb-up,unknown#3 正解)+**Part B BridgePlace:44** reject bridge-from-vine。**GT 52/52 + arena ARRIVED everInPocket=false**。❌**但 LIVE replay-0004 -711 仍 churn 2438t/~122s,pocket 1745,vine-climb 仅 40×**(journey 仍 **ARRIVED**=jank)。⭐divergence:arena 过靠 A* 的 **pillar-AROUND**(Part B 撤 bridge 后更便宜),**live 几何无 around**(parkour→vine→pillar-AT-TOP,vine REQUIRED)→arena 给了 live 走不了的逃逸=false green。Part A+B+fix Q+arena **KEPT**(uncommitted,GT-green,sound,无明显回归,作下 cycle foundation)。**NEXT:精化 arena=force vine 为唯一 ascent(删 pillar-around,从 replay-0004 envelope 真几何切)+re-add VINEDIAG 诊断 live vine-grab 为何不 sustain+爬到顶无 pillar 逃逸才算 faithful-pass+live ≥3× 验**。
  - **2026-06-25 续续(✅✅突破成果)**:① **validation-blocker 终解**=`mc.debug.replay {replan:false}`(经 mc.script.eval)force-path 确定性 A/B,replan variance 不再卡。② **root cause 终定**=-711 vine FREE-HANGING(挂 canopy 无背墙→vineWallYaw=null→Part A press-wall 不适用)。③ **fix R `walkerVineFreeHangClimb`**(default ON;wall-less vine continuous-jump+slew drive+vertical hold;ServerPlayerAvatar+JUMPING_FIELD)修好 **CLIMB**——GT 52/52+free-hanging arena climbedTop=true+**full-journey replan:true pocket 1745→367(5×)/churn 2438→~964(3×)/ARRIVED z282**。KEEP(uncommitted,GT-green)。⚠**残:full-journey -711 仍 churn ~40s(簇 -711~-716)= parkourAscend2 落点 UNDERSHOOT 穿 vine 掉 pocket 底(foot=water→freeHang 不 engage)→浮回 vine 才 climb** = landing-undershoot(独立 genre)。**验证挑战**:arena pocketTicks 是 TOP-dismount artifact 非 bottom-landing;replan:false 在到 -711 前先 wedge 于上游 open-water drift -703,487(#49);replan:true 噪声。→landing fix 需先「精化 arena 捕捉 bottom-landing undershoot」或「先修 #49 上游 drift 解锁 replan:false 直达 -711」做干净 A/B。⭐**fatigue ~565 turn,不盲夹 landing fix,留 fresh cycle 配好验证台再做**。详见 memory fix R 段。

### ✅ 本会话已修(master)
- **4a23b00 climbOutTax(XZ 目标)** — per-rise 罚「出水上岸偏向高岸」,让 A* 挑最低岸出水口。永久 A/B arena `waterClimbOutRouteArena`(税off 爬+1 高岸 maxY221、税on 改走平岸 maxY220)。
- **0cebe97 submergedTax(核心根治)** — 真根因:`PathFinder` 4 条水税(waterCellTax/descendTax/submergedWaterCost/climbOutTax)全门控 `goal.ignoresY()`→**只对裸 XZ 目标生效**;正常 `goto pos` 是 `Goal.Near`(带目标 Y)→水模型全失效→A* 把浮力 bot 路由到淹没河床(stepDown,canStandAt 让任意水格可站)→深水 churn。修=新 `Goal.targetPos()`+`diveGoal()`(水下目标=故意潜水豁免),对陆上目标罚「**下潜**进淹没格」(只罚下潜→pillar/dig climb-out arena 不退化)。**LIVE replay A/B:停顿 58s→14s(-76%)、journey -34% 更快、z1956~1947 深水 churn 簇整簇消失。GameTest 109/109+42/42。**
- **5ac9b3f 证伪记录** — climbOutTax 放开到陆上目标 backfire(罚出水→bot 赖水里→复活下潜 churn,replay 14s→37s),保持 XZ-only。
- **66ef356 knob 接 setting** — pathfinderSubmergedWaterCost 此前未接进 mc.bot.setting,补 setter/snapshot/schema,可运行时调参。
- **9cc617a submergedTax 默认 40→80** — live A/B 再砍一半:停顿 14s→7s(-88% vs 58s 基线)、窗 4→2、z1883「被迫下潜」窗整窗消失。抬罚方向安全(只罚下潜)。GameTest 109/109+42/42。

**进度:journey 停顿 58s→7s(-88%),端到端到达、大幅丝滑。**

### ✅ z1973 漂移上岸振荡 —— 根治(0e08d47)
逐 tick + block 探针推翻「commit 失败/相机射线」初判:真因=block-less 破岸 dig 每 tick 从 LIVE(bob foot.y 翻 208↔209 + 侧漂)重算 riser→**同一岸列在 foot.y 与 foot.y+1 两高度都挖穿**→岸面挖到水线、下一列变新 +2 墙→无限横跳(ashoreTick 162)。修=`Walker.waterClimbDigRiser` 闩锁:①锁住正挖 riser 块实心就一直挖它;②选新 riser 挖前向列**顶层实心块**(沿列向上找顶=对 bob 不变);③仅 `top.y>foot.y` 才挖(+1 台阶本可上,挖它会把岸挖到水下)。**driftArena 162→74**(swim→挖一块→上 +1→上 +1,零横跳零重挖),断言收紧 ashoreTick<120。GameTest 109/109+43/43。

### ⭐⭐ 关键 live 发现:反向 leg goal 本身不可站(坏目标,非寻路 bug)
relaunch 新 build live 跑 (2356,1986)→(2350,1820):bot 多次逼近 d=2~8 又弹回水里 d=80~109,A* **goalReached=0(全 70 段)**;逐格读目标列 **(2350,*,1820) y60-64 全 stone、y65 唯一 1 格 air pocket(石头 y66 顶)=站不进→目标格不可站**。canStandAt 正确拒绝→A* 烧满 60000 节点/repath(4.9s)→bot 邻近水里振荡。**换可站 goal (2343,63,1824)→outcome=SUCCESS、84s 端到端到达、archive 量化仅 1×3.5s 停顿窗(z1956 首次入水)、无西向绕路**。→**核心寻路 + drift-dig 修都 OK;旧 stop-hook 反复报的「journey churn/异常」≈坏目标 thrash。**

### ✅ 不可达/不可站 goal robustness —— 根治(783aa32 + 21807a2)
`Walker.snapGoalToStandable`:Goal.Block 目标若 `!world.canStandAt`,半径 6 内搜最近可站格(用 planner 同一 canStandAt 谓词→规划+arrival 一致)替换 goal。**关键门控 `world.isKnown(target)`**:长途起点离目标 166 格→目标 chunk 未加载→canStandAt 读 void→若首 tick 就 no-op 且置 checked 标志则永不重试;改成目标 chunk 加载后(bot 进渲染范围)才一次性 snap。新 arena `goalSnapBuriedArena`(实心石柱目标→snap 邻格 ARRIVED@31)。**LIVE 验证**:坏目标 (2350,64,1820 埋石山) 整程→snap log「→(2348,64,1820) d=2」、166 格端到端到达并 settle(active=false 静止不动)、pathChart **outcome=SUCCESS reached=true**、无西向绕路。GameTest 109/109+44/44。

### ⬜ 残留真凶(系统性,大工程 deferred):深水 +2 岸 climb-out 原地打转
**逐 tick + step 指针定根(snap journey archive,tick 3537-3722)**:水谷停顿(5.3s+3.6s @ (2360,62,1879))= **step 冻结在一个浮力 bot 上方的 +2 climb-out 节点**(node y64,bot 浮 y62)。`within` 守卫 `!(inWater && dyNode>0.5)` 正确不让前进到没够到的 climb 节点,bot 到了该节点的 XZ 但爬不上去→**对着正上方节点没有水平 aim 方向→smoothLook 原地旋转(yaw 扫满 360°=pathChart maxYawErr179 之源)**,直到 repath 把它带走。climb-out 接管(pillar/dig)本该 engage 但被打转/侧向动量搅乱不可靠(=memory「接管延迟+可靠性+burst-crab」)。**多 journey 验证(本会话 2 条)证此为系统性**:南向到 snap/可站目标=到达但中段 1 个 5s 打转窗;北向 (2356,70,1986) 整程在起点水区打转 130s 没到达——此水饱和地形多个目标都落在 water-climb-out 点。**根治=专项重写水岸 climb-out 执行**(浮力 +2 岸:climb 节点上方 aim 稳定[别打转]+ 接管可靠引擎,~15 prior+本会话尝试均证非增量能成);drift-dig(0e08d47)已修「挖过头」一类,但「到了climb节点XZ却打转不engage」是另一面。**别在会话尾部仓促改 aim/heading(4 次 forced-heading 尝试都 revert 过)。**

### ⬜ 新失败模式根因(已定位)+ dig actuator 已证伪(revert):水平水域 bank-face wedge
**逐 tick walker 日志(walkerDebug)定根**:重跑南向 climb-out(TP 2361,66,1895 → goto 2361,64,1860),archive 量化 = 20s 跨 33 格、**85% 在水里**、median 0.09 b/tick(半速)、**2 个停顿窗 2.5s@(2361,62,1880)+3.7s@(2361,62,1870)**,全在水里。walker 日志:停顿处 `move=walk onG=false inW=true undW=false node=2360,62,1869`——**节点 Y==foot Y**!`wantClimbNow=(cwp.y>foot.y)` 恒 false → climb-out context 永不 arm → `waterClimbStall` 恒 0 → 破岸/搭台 actuator 全程**零触发**(grep 证实)。bot 顶着一道岸 face(planner 把平 Y 节点路由到 face 后)`cur2` 卡 ~1.0 撞墙,直到 ~64 tick 后 A* 撞运气重路由才脱困。**这是和 +2 竖直 climb-out 不同的一类:水平 bank-face wedge。**
**尝试(Walker,5 处:`horizWedge`=水中+前向solid face+`noStepProgressTicks>30`→折进 wantClimbNow;dig trigger 加 `||horizWedge` 跳过慢门槛;riser 加「同 Y body-level face 直接挖穿」分支)→ GameTest 44/44 不回归(driftArena 75),但 LIVE 证伪:** dig 触发了(block-less-dig fires **0→213**),**可是同 Y foot-block dig 是错的 actuator**——浮力 bot 把 foot 层挖掉后**下沉进挖空格、在更低处再 wedge、再挖**=往下挖坑;riser 闩死在 `2359,62,1863`(够不到了仍 latch)213 次空挖,bot 从 y62 沉到 y59,**停顿 2.5+3.7s 反而恶化成 11.2s、全程 20s→31s**。video 也报「卡在地下泥土矿洞」。**已 `git checkout` revert 回 f4da16f(干净,重编译过)。**
**下次正确方向(别再挖 foot 坑):** ①规划层——`canStandAt`/move 生成时,浮力无方块 bot 不该把平 Y 节点路由到水面岸 face 后(不可达);或给这类 cell 加 reach/penalty。②执行层若要救,**不是挖**:把够不到的平 Y 节点判「passed/skip」+ 标 avoid-point 快速重路由(不 gouge 地形),或在**水面层**开通道让浮力 bot 平游过去(不在 foot 层挖坑)。trigger(horizWedge 检测)本身是对的、可复用;**坏的是 dig actuator**。

### ⭐✅ 真根因纠正 + 修复落地:水中 yaw 振荡(master b30ab05)
archive WalkerSample 推翻上面「face wedge/dig」诊断:停顿处 `aabbOverlap=false`(无碰撞=无岸 face)、浮水 STANDING。真相=**flat 水节点上 yaw 振荡**:stall 窗 yaw 摆 96-176°、净速 0.37-0.88 b/s;同路径 heading 稳定段(摆 22°)=4.6 b/s。yaw 摆抵消前进推力→「原地不动」=停顿,**同根因也是 pathChart maxYawErr180 + 视频镜头跳变**。修 b30ab05:flat 水节点在 noStepProgressTicks>15(~0.75s)时也用宽 aim dead-zone(CLIMB_AIM_DEADZONE_SQ=4)锁 heading;门控 no-progress 保 climb-out 精度(无门控放宽→noBlockArena 回归)。**GameTest 44/44 + live 停顿 2→1、6.2s→2.1s(-66%)、yaw 振荡 3.5-6→1.6°/tick、零误挖、SUCCESS**。残留 1×2.1s = slow-progress 振荡(缓慢推进重置 noStepProgressTicks→门控漏)。证伪:放宽推广到「无 imminent climb flat 水」→ waterClimbOutRouteArena 回归(漂离 +1 出口),太脆弱 revert。

### ⭐✅ carrot-swing 远 aim 稳定(master c733df7)
b30ab05 残留的 aim2>deadzone carrot-swing(LOS 被岸挡→carrotPoint 坍缩近节点→短 aim 矢量随浮水漂移快转、bearing 摆 96-176°)。修 c733df7:水中按 raw carrot bearing 反向次数累衰减 `yawThrashTicks`;分数≥6 且前向 lookahead 段无爬升节点(climbAhead=false,保 +2 climb-out 挂载精度)时 aim 到 step+3 远节点(长矢量 bearing 稳)。GameTest 44/44(waterClimbOutRouteArena maxY221 不回归)。LIVE flat-水 carrot-swing 簇 3 窗/6.5s→1 窗/2.2s。

### ⭐ 真根因再纠正(逐tick walkerDebug+archive 实测,2026-06-15):水谷打转 ≠ 「+2 climb-out 执行卡死」
**z1881 南向 climb-out 复现(TP 2361,66,1895→goto pos 2361,64,1860,walkerDebug+archive)定根,推翻 §「残留真凶」整段的「step 冻结在浮力 bot 上方 +2 节点 / climb-out 接管该 engage 却被搅乱」理论**:
- **climb-out 执行器(pillar/dig takeover)整程零触发**(walkerDebug 1635 行,climb-out/bank dig/pillar/topped 行 = **0**)。A* 没路由「浮力 bot 上方 +2 竖直节点」;它路由的是**可走的 stepUp 楼梯**(y63→64→65,move=stepUp 链)。climb-out 执行器**正确地保持关闭**(不需要)。
- **真打转 = 干地 stepUp 楼梯的近节点 aim 振荡**。archive 三停顿窗:①(2362,62,1885)inW=true onG=true=2 深水里**贴床趟水慢**(~0.11 b/tick,node 是 19 格外的平 y62 节点);②(2359,63,1864)onG=false inW=false **yawRange=405°**=干地 stepUp 链上,`aimAtWaypoint=wp.y!=foot.y` 对每个 +1 近节点(cur2 0.5-1.4)snap→短水平矢量 atan2 随 step bob 摆→多级楼梯累成 360°+ 镜头转(climb 本身在进展);③(2361,68,1860)=到达 goal 旁的 idle 抖。
- **结论:这不是水域/climb-out 执行域的系统大工程,是通用「干地 stepUp 近节点 aim 振荡」**(=旧 §残留小项的「z1987 干地 stepUp bob」同根,但严重度被低估)。

### ⭐✅ 干地 stepUp 楼梯镜头打转根治(master 5a0ca93)
**先证伪 hold-yaw 版**:`landStepUp` 用 CLIMB_AIM_DEADZONE_SQ 冻结 heading → **buoyantwallarena 回归**(冻结的 heading 钉死一个错方向,+5 水岸出水 stepUp 顶岸失败 maxY211.25)。**冻结 heading=错路**(=旧 §「4 次 forced-heading 都 revert」同坑)。
**改 bounded-lookahead(过)**:land + 平缓 +1 step,且水平已在该 step 节点 1 格内、还有后继节点时,aim 改指 **step+1 节点**——给真实「朝楼梯上方」稳定 heading,不冻结(故不钉死错方向;+5 水岸出水后继是前向 ledge walk,只会更稳)。排除 parkour + >+1 跳。
**验:GameTest 44/44(含 buoyantwallarena)。LIVE z1881 南向 climb-out:干地爬楼 mean|dyaw/tick| 405°区间→0.50°(max 8°=slew 上限单次平滑转)、29s→20s、停顿窗 3→1(仅到达 goal 旁 idle settle)。video:过岸「平稳前进、无停顿或抖动、移动轨迹平滑」。**

### ⭐⭐✅ 端到端全程验证(三修齐):165 格水谷 0 停顿
**TP (2356,67,1986)→goto pos (2350,64,1820)[snap→(2348,64,1820)],全 165 格 N→S 多段水域 + 上岸台阶。archive:outcome SUCCESS、67s、z1986→1820 端到端到达、**停顿窗=0、0.0s**、whole-journey mean|dyaw/tick|=1.55°(max 88 单次 slew)、干地 onGround mean=2.19°(无 405° 打转)。video 双通道:journey 中段 NORMAL「沿峡谷水渠持续向南移动、行进路线连贯」「沿河谷水域持续向南推进并顺利上岸」。** vs 会话初 58s 停顿 / 上一条 video run 同路 4 窗 8.8s → **本条 0 停顿**。三修累积(b30ab05 宽 dead-zone + c733df7 far-aim + 5a0ca93 stepUp lookahead)把水谷停顿簇清零。
### ⭐ 随机长途多实验(部分 clean,但暴露真残留)
- **J0 全 N-S 水谷 165 格**:0 停顿、SUCCESS。
- **J1 东向上山 113 格**(含 parkour2/3 爬坡 y64→74):**0 停顿窗**、SUCCESS。
- **J4 山顶→水谷 90 格**(y79→63):1×1.8s(2 深水趟水)、SUCCESS。
- **J5 西向 2461→xz(2330,1740) 130 格**(干地起):**4 停顿窗 ~6.4s**、SUCCESS——①1.6s@(2461,70,1824)airborne(起步跳)②1.3s@(2392,63,1793)干地③**1.4s@(2335,62,1742)+2.1s@(2335,62,1743) inW浮水 onG=false yawRange131/96=floating-water yaw-spin**(逼近 goal 旁 +1 岸的最后一段水)。
- **测得 goto 起步 latency=0.45s(quick-start stub 工作正常,起步停顿非真问题)**;video 反复报的「数秒才动/溺水」=lag + bot 到点停在水里/墙边的 idle 误读(实测 hp/food=20 不掉血)。

### ⬜⬜ 真残留核心未根治:浮水逼近 goal 的水中停顿 —— 诊断已纠正=step-pointer thrash(非 aim-spin)
**逐 tick archive(J5 replay-0008,z1742-1743 停顿窗)纠正了「carrot-swing aim-spin」初判**:停顿处 `move=walk/parkourDescend2d1`、onG 浮力 bob 翻转、bot **仍在前进**(z 1744.3→1742.0 ~0.08/tick 慢),关键是 **step 指针来回跳 2→3→4→4→2→1→1→…→2(4→1 倒退!)**=浮力 parkourDescend 入水时**步指针 overshoot/reset 反复**→aim 追被 reset 的节点→yaw 摆 40-131°。**这是 step-tracking(overshoot-resync)域,不是 aim/carrot 域**(见 [[reference_walker_overshoot_resync_fix]])。
**❌证伪:把 far-aim 扩到 climbAhead→aim climb 节点** → GameTest **summitarena 回归**(浮水 thrash 时 redirect 把 overhead pillar 爬升 ARRIVED@y226 早停)→ **已 revert 回 5a0ca93**。教训:水中 thrash redirect 不能在 climbAhead 时 aim climb 节点(会劫持 pillar);且 J5 根本机制是 step-thrash 非 aim。
**再纠正(读 step-advance 源码 1287-1388):该循环 step 只 ++、永不回退 → J5 的 `4→2→1` 倒退是 REPATH 重置 path(adoptPath step=1)**,不是 overshoot-resync 回退。即:浮力 parkourDescend 入水逼近 goal 时**反复 repath**(每次重置 path+step→aim 追新近节点→yaw 摆 + 慢 wade)。与早先量到的「19 repath/段」churn 同源 = **规划层 repath 频率问题**(P1 候选①)。**✅拿到干净 repath trace(latest.log 未轮转过 J5)**:近 goal z1743-1746 处 repath 反复 `goalReached=true` pathLen 6-11、00:19:49 一秒内 3 次,**foot.y bob 64→62→61**→`fellOffPath`/`wedged`→`safetyRepath`(Walker:798)→line 876 kickoff,cheap search 同 tick 完成→activeSearch=null→下 tick 再 repath=churn。每 repath 重置 path+step→aim 追新 node-1→yaw 摆。
**❌证伪#2:safety-repath 防抖(SAFETY_REPATH_MIN_TICKS=10,gate `!pathBestEffort`)** GameTest 44/44+109/109 过,但 **LIVE J5 重跑反更差(4 窗 6.4s→7 窗 12.2s)**:①gate `!pathBestEffort` 没命中近 goal churn——**近 goal 的 held path 本身是 pathBestEffort=true**(horizon/eager-precompute 提交的),只有 search RESULT 是 goalReached=true,两者不同→防抖没作用于目标场景;②反而 delay 了干地/airborne transient 该有的 repath→新增/加长干地停顿(起步 1.6→4.0s、z1793 1.3→3.6s)。**已 revert 回 5a0ca93。**
**正确方向(下次)**:①治本=让浮力 bob 别误触 `fellOffPath`/`wedged`(noStepProgressTicks/wedged 逻辑:水中 bob 且净进展时不算 fell-off);②或防抖键于「search RESULT goalReached=true + inWater + 净进展」而非 pathBestEffort。**GameTest 不覆盖此长途水边 churn 场景(arena 全过仍 live 退化)→必须靠 live archive A/B 验证,别只信 GameTest。**注:残留是慢 wade/churn(bot 仍到达 goal)非硬冻结,中低severity。

### (旧标题保留)floating-water yaw-spin —— 已并入上条(step-thrash 才是真因)
**多条随机 journey 复发**(J4 1.8s、J5 3.5s、J0 残留):bot **浮水**(onG=false inW=true)逼近一个 +1 岸/climb-goal 的最后一段水时 yaw 摆 96-131°→thrust 抵消→1.4-2.1s 停顿。**诊断**:far-aim(c733df7)在 `climbAhead`(前向有爬升节点)时**故意抑制**(保 +2 climb-out 挂载精度)→逼近岸 goal 时 far-aim 关闭→carrot 坍缩短矢量→浮水漂移让 atan2 摆→spin。这和干地 stepUp(5a0ca93 已修)是同构问题的**水中版**,但 5a0ca93 只覆盖 `!inWater`。**正确方向**:把 bounded-lookahead(aim 下一节点、不冻结 heading)推广到「浮水逼近 +1 climb-goal」;**风险=aim 雷区**(hold-yaw 版曾 regress buoyantwallarena;far-aim 几何放宽曾 regress waterClimbOutRouteArena)→**必须干净逐tick复现(注意 walkerDebug 刷屏致 latest.log 轮转,需大日志/降噪)+ 全 44 arena + live video 验证**,别在 deep-context 仓促改。

## P1 — 规划层优化(用户 2026-06-16:「规划层也是时候优化了,删掉延后描述,随时可做」→ 不再 deferred)

**⭐ 现状更正:渐进式寻路已 substantially 实现**(见 memory [[project_progressive_pathfinding_idea]]):quick-start stub(短搜立即交段 this very tick)+渐进水面 bee-line stub+eager precompute(从 commitEnd 预搜下一段)+horizon/soft/frontier commit+idle-slice。随机长途双段均 0 执行停顿到达 → 规划层无阻塞性 bug。**剩下是效率非正确性。**

**具体优化候选(需先量化,再谨慎动 A* core;A* core=雷区,必 arena+live video 双验):**
- ⬜ **repath 频率**:单段 journey ~19 次 repath(多为 eager best-effort 从 commitEnd 预搜,2s 内提交 4 次几乎相同路径)。当预搜结果与现路几乎相同/现路仍 valid 时跳过重提交,省 CPU、减微停顿。
- ⬜ **goto 起步 latency**:量化 goto-issue→首动 tick 数(quick-start stub 已缓解,确认是否仍有可感停顿);若大,强化 stub 触发。
- ⬜ **far-goal 路径质量**:长途绕路/best-effort 段是否最优。

### ⬜ 残留小项(执行层,效率非停顿)
- z1956 首次入水 ~2s 水中绕圈(pure-pursuit overshoot 浮水)、z1885 2 深水趟水慢——本条 165 格全程已 0 停顿窗,被吸收/不再触发停顿阈值。

### ✅ video 双通道已恢复
video 转录端点已恢复 HTTP 200,本会话 stepUp 修 + 全程验证 + J1 均跑了 video 双通道(过岸 NORMAL「平稳前进/向南推进/顺利上岸」)。注意:bot 到达 goal 后 idle 停墙边时 run.py 会把静止画面报 ANOMALY「卡在墙前」——那是到点静止非寻路异常,验收时按位置/archive 区分。

### ⬜⬜ P1 新真根因(2026-06-16 随机长途 J1 跨水):XZ 水域横穿下潜河床 = best-effort partial 在 budget 耗尽时 h-主导提交深节点(非 cost-model)
- **复现**:goto xz(2480,1640) 从陆地 (2331,64,1746) 起,跨第一片水域时 bot 一进水**直接潜到 y54 河床**、沿底 swimTraverseBreak/diagDown、水下挖沙、对岸陡坡爬不上、整程 22s(442 tick)在水下、2 次镜头大摆(≈300°/96°)、然后 repath 爬回水面 y62 才平游。video 双通道全程 ANOMALY。archive replay-0002 段节点 `submergedEye:True` 连片 = **计划就走水下**。
- **证伪修(未提交已 revert)**:`waterCellTax` 二元 +80 submerged surcharge → 改 ×submersion depth(数 foot 上方水格 cap6)。GameTest **109/109 全过含 6 水 arena**,但 **LIVE 完全无效**(重跑 entry minY 仍 54、水下 442 tick)。
- **根因确诊**:`lastPath expanded=6000`(节点预算耗尽)+ goalReached=false → A\* 交 **best-effort partial**;选点 `bestSoFar=argmin(h + g/COEFFICIENTS[i])` 大 coeff 把 g 除没 → **h(纯 XZ 距离)主导** → 下潜到 XZ 更近的深节点 h 最小直接胜出,**g-cost surcharge 被除掉对 best-effort 无效**。远 XZ 目标(175 格)超 render → 恒 best-effort partial,所以「partial 走深」稳定复现。
- **正确下一步(规划层,arena+live 双验)**:①best-effort/bestSoFar **只在浮力可达(水面/陆地)节点里挑**,排除深潜节点(类比 water-start 的 `bestAshore`);②或节点扩展时**浮力可达性过滤**(陆地起点穿水不把深河床当可站);③或深潜节点加 **H 惩罚**(让启发式本身反映「浮力到不了」)。
- **教训**:per-cell g 税救不了 budget 耗尽时的 h-主导 partial 提交;GameTest 全过 ≠ live 有效(本会话第 3 次 GameTest-pass/live-fail,前两次=debounce/aim-extend)。
- **次要残留**:近 goal 浅水/沙滩**绕圈** ~35s(goal XZ 在水/未载入对岸,贴水边 gdist 31↔39 摆才挤过去)。
- 工具:`scripts/journey_runner.py`(位置稳定轮询的链式 journey runner,免 cancel-storm 误报)。
- **本轮净提交=0(诚实负结果),树洁净 HEAD 00b8dcf。**

### ⬜ P1 水域横穿:第 2 次尝试(buoyancy planning gate)也证伪 → 真修在执行层(swim-up)
- 实现 `divesPastSurface`(PathFinder 硬 admissibility gate:XZ goal 下潜 move,`to` 上方 3 格全水=水面够不到→拒绝;ignoresY+diveGoal 豁免)。修正版(查「水面是否够得到」而非绝对深度计数,避免深水探针饱和)**GameTest 44/44 全过**,但 **live 更糟**:bot 在土墙陡岸 y51-53 卡 ~40s + 水底挖泥(baseline 是下潜后 ~50s 自己游过)。
- **真根因**:bot 从 y64 悬崖 stepDown 进水靠**下沉/坠落物理**直沉 y59→y51,planning gate 拦不住物理下沉;又禁了 A\* 规划顺陡岸绕行 → 岸底 wedge。
- **真修方向(执行层)**:浮力 bot 在深水(eye 远低于水面)时 Walker 主动按上浮键游到水面,再按 path XZ 在水面推进(忽略 planned Y 深度)= autoSwim/swim-up 域。次选:规划层选更平缓入水岸。
- **本会话水域横穿净提交=0**(cost-surcharge + planning-gate 两次均证伪 revert)。GameTest 全过≠live OK(第 3、4 次)。树洁净 HEAD 00b8dcf。

## 2026-06-16 随机长途 787 格旅途 SUCCESS + 干地对角楼梯 YAW 锯齿根因(数据坐实)
- **旅途**: goto XZ(1850,2150) 距起点(2482,1681)~787 格,跨山/水/林。poller 报 ARRIVED t=293s dist=3.3。全程强力净推进,所有视频异常均为 transient 边界 episode 并自行恢复(逐 tick 采样佐证:水中游动 yaw 恒定 48°)。**端到端成功**。
- **archive 定量(replay-0002-...073032.json, 8679 ticks)**: mean|Δyaw|/tick=2.06°(整体丝滑)。最严重 yaw 抽搐窗**几乎全在干地 inW=False y76-92 的陡对角爬山段**(rev=5/20t、sum|Δy|~120°),唯一水窗是起点 launch。
- **隔离重跑爬山(TP 山脚→goto 山顶,3Hz 实测 pitch+yaw)**: **pitch 全程 = +0.0**(纹丝不动)→ 视频「仰视天空↔地面」是误读(浮空岛 scenery+yaw 扫视+step bob)。YAW raw 在 380-485(~100°带)反复摆,单样本 ±20-32°。
- **根因**: 对角楼梯交替 walk(aimAtWaypoint=false→carrot)/diagUp(aimAtWaypoint=true→精确近 wp)步,**aim 源每步切换**→targetYaw 跳 ±20-30°,smoothTargetYaw EMA(alpha .5)只砍一半→持续游走。carrotPoint 在楼梯上被 riser 挡 LOS(losWalkable break)塌缩成近节点,也给不了稳定远 aim。
- **FIX(Walker.java,attempting,pending live verify)**: 镜像已验证的水域 far-aim——干地爬 GENTLE 楼梯(只上、无 >2 上跳、无下降)时瞄固定远节点 path.get(step+3),forward-dot 守卫防楼梯拐弯瞄反。GameTest 守门 ascentSpeedArena + 44 arena,live 重跑爬山验 yaw 带收窄。
- **残留(未做)**: 终端 radius-0 XZ 目标在悬崖上 bob/dig 无法收口(部分是 radius-0 选择产物);anti-spin freeze 等保护是 isInWater 门控、干地近目标振荡无保护。

### 2026-06-16 续:楼梯 far-aim fix LIVE 否决 + REVERTED(第5次 GameTest过/live不灵)
- fix(干地 gentle 楼梯瞄 step+3 far 节点)GameTest 109/109 全过,但 **live 重跑同一爬山 yaw band=109°≈baseline ~100°,reversals=19,bigSwings=9,无改善**。爬升段 yaw 仍狂摆(36→71→68→104→48...±35-56°)。**已 git checkout revert,树回 00b8dcf**。运行 client 二进制仍含该 inert fix(无害,GameTest 净、爬山正常),下次 relaunch 复 baseline。
- **深层根因升级**: 山路 A* 路径是**之字形**(cardinal 步逼近对角)→ 任何**单节点 aim(近 or 远)都随之字摆**;step+3 far 节点 3 步之字仍摆。水域 far-aim 有效因水路直。
- **真正方向(未做,需迭代调参+视频在线)**: 不瞄单节点,瞄**路径趋势方向**(对未来 K 节点做位移平均 / 更大 lookahead 平掉之字 / 对一致源加强 EMA 平滑)。需多轮 build+relaunch+climb-poll 定量迭代(yaw band 指标),且 pitch 全程 0 无需碰。
- **注**: 2026-06-16 当次 vision 端点 HTTP 400 连续失败,视频通道临时不可用,只能靠 RPC yaw-poll 定量;迭代调参待视频恢复。

### 2026-06-16 续2:第2次随机远途 SUCCESS + 终端 radius-0 settle 尝试(罕见/不可复现→REVERTED)
- **journey2**: goto xz(2600,2400) ~707格 → ARRIVED t=431s dist=0.6。中途一次 ~15s 水岸 climb-out 真停顿(自恢复),其余强力净推进。**两次随机远途均端到端到达**(j1 787格/dist3.3, j2 707格/dist0.6)。
- **pathChart(j1, pathchart-0002-...169.png)已读**: 轨迹紧贴 plan 直线对角,heading 实际 yaw 紧贴 target bearing,avgSpd2.72。maxYawErr=180° 峰值在终端 radius-0 绕圈缠团。**整体丝滑**。(hook 称 pathChart 未生成=误判,实际 auto-dump 在 debug/pathchart-*.png)
- **视频 over-read 再确认**: j2 多次报「高频急转/原地打转/死循环/潜水浮出」,但逐tick位移=稳定强推进 or 到达后 y 恒定 idle。**video 在浮空岛+水域地形把合法转向/idle bob over-flag 成 ANOMALY**(同 pitch=0 却报「看天」)。客观仪器(pathChart/archive/RPC poll)才是 ground truth。
- **终端 radius-0 settle fix(REVERTED)**: 加 near-goal-settle(ignoresY 目标 d≤16 且 totalTicks>100 无改善→ARRIVED,复用 bestDistToGoal 单调 bob-proof)。GameTest 109/109 clean。但**3 次 live repro(水柱/干地高柱/平台)全部无法触发**——因 XZ goal 忽略 Y,bot 只要任意高度 foot 块落到 (x,z) 列即 goal.reached,「精确列任意Y都够不到」极难构造,实际 bot 都正常到达。→ **无可观测证据它触发,按 fix_verify_discipline REVERTED**。终端 churn 比判断的罕见;视频终端 anomaly 多为 idle-water-bob 误读。fix 逻辑严密+GameTest clean,待确定性 arena 触发器再用。
- **本会话净提交=0**: 价值在诊断+验证(两次旅途证明大地形整体丝滑可达 + 精确刻画残留 + 证明 video over-read),非新提交。残留真问题均为高回归雷区(水岸climb-out/climb-aim)或罕见(终端radius-0)。

### 2026-06-16 续3:settle arena 证明 settle 干地冗余 → REVERTED(关键认识)
- 建 nearGoalColumnSettleArena(5格实心柱占目标XZ列+place/break off)试图确定性触发 settle。45/45 通过,但日志 step=ARRIVED **ticks=85 < NEAR_GOAL_SETTLE_TICKS=100** → settle 路径**未执行**;且 pos y=215.3=bot 爬上柱顶在顶端够到列→goal.reached 正常触发。
- **关键认识**: 干地上 Walker 现有 best-effort/frontier-arrive 对不可达 XZ 目标**本就快速到达(tick85)**,我的 settle(>100tick)**干地冗余**;只对水中浮力 bob 终端 churn(现有 water-churn-giveup 偶尔不触发那种)有用,而那个**无法在 arena 确定性复现**(水+浮力+恰好差1格)。→ 无法可观测证明 settle 独立生效(arena 证明的是它**没执行**),REVERT。
- **47Hz 高频实测(回应「仪器太慢视频才对」)**: pitch range=0.0(视频「看天」=把浮空岛误读)、yaw 反向仅1.3/s(非高频抖,是跟山路165°平滑大转向)、水中idle 0反向完全静止。20Hz archive 终端 y冻结。**客观仪器是ground truth,video高召回低精度需逐条核实**。
- **本会话定论**: 净提交0。架构可行(两次随机远途787/707格均端到端到达、pathChart丝滑、47Hz无高频抖)。残留=水岸climb-out偶发停顿(高回归雷区)+终端radius-0(干地已被现有机制处理,水中罕见且不可确定性验证)+蜿蜒地形合法大幅转向的「镜头晃」观感+video误报。这些跨越本会话修复范围/风险/可验证性。

### 2026-06-16 续4:20Hz三重交叉定位唯一真停顿(修正前面过度乐观)
- **方法论修正**:停顿检测**没有单一指标可信**——①XZ位移:把陡爬(y92→100、XZ小)误判成停顿(假阳),也会被repath重置的step骗;②step索引:repath时重置→大netXZ的"step停"是假象;③真停顿判据=**step停滞 AND netXZ<~5 AND |Δy|<2 三者同时**。我之前8s稀疏轮询+单指标多次得出错误结论(把~100s水岸churn判成"~15s自恢复"、把陡爬判成停顿)。
- **唯一真停顿(journey2, 20Hz三重交叉确认)**: **ticks 3349-5334 ~100s 在 (2372,63,2168) 水岸**——step卡4-8、netXZ 1-9、y平62-63,持续churn stepUp/stepDown/diagDown/fall2(inW 47-93%),~100s仅漂~6格才磨出。**真实严重的水岸climb-out停顿**(=trend-aim破过的waterClimbOutRouteArena同域雷区)。
- 其余archive"停顿"全是假阳性:陡爬山(y在升)or repath重置step(netXZ大几十)。
- **下一步(需新预算)**: 建确定性**水域climb-out arena**(深水池+tall bank,bot须爬出)复现这~100s churn,谨慎修(别回归waterClimbOutRouteArena)。这是剩下**唯一的真bug**;其余"异常"=陡爬合法转向观感+video误报(47Hz已证伪)。

## 2026-06-16 随机长途#3 (2600,2400)→XZ(1820,3150) ~1080格 — 决定性诊断(archive replay-0005, CANCELLED@22min)
全程 1335s 中 **749s(56%)在停顿**。开 live video(run.py)+47Hz hfpoll 双通道核实。
- **pitch 全程恒 0**(多次 6-77Hz poller distinct=1):视频反复报「镜头高频抖动/俯仰跳变」物理不存在=over-read。yaw 0.9-1.3 rev/s 平滑跟路。**video 高召回低精度,以 archive/hfpoll 为准**(再证)。
- **Zone A 游泳"卡顿"= 误报**:walk-in-water 1.1 b/s 慢速连续推进,被我 60t/4.5格 阈值误标(非真停顿)。
- **Zone B = 真 bug:badlands 红土水盆 climb-out 死锁**。bot 在 dist 332-386 振荡 18 分钟零净进展。单段停顿 **72.5/44.2/40.1/31.6/30.6/22.6/20.6/20.3/18.4 s**。
  - **A* 两岸振荡**(seg#74/76/77):每 repath 从 bot 当前岸 fall2/fall3 主动坠回水→横游→爬对岸 y64↔71;目标在水盆外 SW,两岸交替"更近目标"→永远弹跳。复杂盆地每 repath 烧 6000–**60000**(满上限)节点→搜索冻结即大停顿。
  - **72.5s 逐 tick 铁证**:bot 死钉 (2023.3,62,2877.7) **yaw 恒=0**(朝 +Z 南岸)move=walk onGround 1↔0 水面bob，**aabbOverlap=0(非卡墙)**，step 钉2。每 200t repath 重提**同一向前顶岸路径**→破岸兜底(Task#36 node.Y==foot.Y)在此几何**未触发**→钉 65s，靠 repath 随机性 yaw 翻 304° 才逃。
- **根因假说**:浮力 bot 水面顶**高于水面 2-12 格的红土岸**(badlands mesa basin)，水平水节点 walk 顶岸 riser 浮力爬不上，bank-face 破岸/pillar 兜底在「深水盆+高 mesa 岸」未生效；A* segment-commit 被 XZ-goal 骗（两岸都"更近"）→bank 振荡。
- **下一步**：建确定性 arena（深水盆+南向 tall 红土岸 y64-71+bot 浮 y62 朝岸+goal 岸外）复现 65s 顶岸钉死 → walkerDebug 定位为何 yaw 锁 0 不破岸 → 修（破岸兜底覆盖此几何 / 无净进展更早强干预）→ GameTest 109/109 + live 重跑。**别回归 waterClimbOutRouteArena/deepWaterClimboutDriftArena**。
- 残留次要：Zone A 水中 1.1 b/s 慢（可选提速）。

## 2026-06-16 真根因确认=spinFreeze(推翻 far-aim/死区两假设)+ 修复
用 live DIAG 日志(临时加在 Walker aim 块,打 aim2/heldYaw/bearing/flag)在真盆地复现实测:
- aim2 恒 >dz(4.0)→**dead-zone 无关(假设A否)**;从无 DIAG-FARAIM→**far-aim 无关(假设B否)**。
- heldYaw 冻死常数(-167°→69° 恒 500+tick),bearing 算得对(指 waypoint)却不转,foot 钉死,noStep→498。
- **唯一能在 angleDiff 巨大时冻 p.getYRot() 的=spinFreeze(Walker.java ~2250):`overWater && repathsNoProgress>CHURN_REPATH_CAP(3)` gate 掉 yaw slew**。churn over water→冻陈旧错朝向→死推岸→零进展→repathsNoProgress 持续→无限死锁。
**修复(已改 Walker,待 GameTest+live 验+commit)**:spinFreeze 加 **target-stability 门控**——`lastAimYaw`/`aimStableTicks` 跟踪 smoothed aim;`targetFlipping=aimStableTicks<AIM_STABLE_TICKS(10)`;spinFreeze 仅在 `targetFlipping` 时冻。winding(每 repath 翻 180°)→aimStableTicks 重置→保持冻结(anti-spin 保留);本死锁(bearing 稳定)→~0.5s 后释放→capped slew 收敛转向 waypoint→逃脱。常量 AIM_STABLE_DEG=8°/AIM_STABLE_TICKS=10。
waterFarAimBankCornerArena=水域隔墙绕行 smoke test(不确定性复现 spinFreeze,真验证靠 live 真盆地)。
**教训**:连续两次静态分析假设全错,DIAG 日志是关键。水域 yaw 冻结 bug 必 live 打 aim 内部状态实测。

## 2026-06-16 续5:spinFreeze 死锁 ✅修复提交 16c5a8c + 3次干净 live journey 验证
- **真根因再升级(超出续4假设)**:spinFreeze 不只冻镜头——line2579 `driveDelta = spinFreeze ? 0 : angleDiff(...)` **把驱动方向也冻成 0**→身体沿冻结的错朝向死推岸。恶性循环:冻驱动→零净进展→repathsNoProgress 持续高→spinFreeze 持续→冻驱动。这才是 18 分钟死锁的执行层闭环。
- **修复(2 处,均针对此死锁)**:
  1. **解耦驱动与镜头冻结**(line2579 去掉 `spinFreeze ? 0`):身体始终朝 aimYaw 走(Δ=aimYaw−cameraYaw,AgentInput 动态纠偏)。镜头仍可冻(防视觉转圈 winding),但身体一动就有净进展→repathsNoProgress 重置→冻结自行释放→循环无法形成。
  2. **target-stability 门控镜头冻结**(aimStableTicks/AIM_STABLE_DEG=8/AIM_STABLE_TICKS=10):仅在 aim 目标真翻转(winding)时冻,稳定~0.5s 释放。
- **验证**:GameTest **109/109**(水域 climb-out arena 全过,无回归);**3 次干净 live journey**(replayMode 清后):
  - #1 (1853,3000)→(2298,2703) 580格干地:mean|dyaw|1.92°、yaw span 456(非winding)、最长停顿1.3s、到达。
  - #2 →(1960,2884) 西渡 lake 7%inWater:**水中 mean|dyaw|2.64°、in-motion >90°flips=0(零winding)**、最长停顿1.5s、穿水到达。
  - #3 →(2400,2400) 700格:mean|dyaw|**1.16°**、终端 last4s net19/yaw125=clean、到达后 hfpoll 8s **YAW=0 完全静止**。
- **复现教训**:'AgentTest' world **跨 reload 非确定性**(地表整体 y≈-60 低海平面,原 basin 的 y57-77 抬升岸 reload 后消失)→mc.debug.replay 恢复悬浮岛 bot 穿地坠 bedrock、TP 复现全失败。basin 抬升岸 climb-out 自然复刻不可得→靠 GameTest 抬升岸 arena 覆盖。
- **video backlog 教训**:vLLM 恢复后积压致视频报告滞后数分钟,大量"村庄打转/静止"是**旧帧滞后误报**;追上后与 hfpoll(YAW=0)一致报"静止"。再证客观仪器(archive/hfpoll/RPC)是 ground truth。
- **残留(非 spinFreeze 域,独立)**:终端 radius churn——goal 不可达 near 时终端 settling 偶有~2.5s/392° 打转 burst(间歇:#2 有、#3/re-test 无),干地非 overWater→与 spinFreeze 无关,属 task#35 settle 域,settle 修曾 REVERTED(不可确定性复现)。

## 2026-06-16 续6:video 通道真根因=qwen小模型 → 换 gemini 修好 → 双通道清洁达成
- **续5「video 不可靠」结论被推翻**:video 所有假异常(把 net75格/窗移动报「静止」、对 hfpoll YAW=0 的 idle bot 报「180° winding」)真因=**OPENAI_MODEL=qwen3.6-27b-nvfp4 小视觉模型能力不足**。
- litellm 端点有强模型(gemini-3.1-pro/flash-preview、claude-opus-4-5、gpt-5.4…)。改 run.py line53 `MODEL=os.environ.get("OPENAI_MODEL") or ENV["OPENAI_MODEL"]` 后用 `OPENAI_MODEL=gemini-3-flash-preview` 启动。
- **gemini 完全正确**:移动报 NORMAL 且描述精确(「红沙纹理向后滑动、陶瓦山体相对位移=稳定前进」「转向轻微平滑、无停顿无打转」),idle 报「静止」,双向准。
- **✅双通道清洁 journey 达成(满足验收)**:replay-0008(2197,2741)→(2040,2860)穿平原/树林/水域/badlands:**archive mean|dyaw|2.30°、flips>90=0(零winding)、最长停顿1.3s、到达** + **gemini 全程 NORMAL=12 / ANOMALY=0**。另一程(→2200,2740)gemini 亦全 NORMAL。
- **教训**:① 视频验证前确认用强视觉模型(gemini/claude/gpt),别用 qwen3.6-27b;② 平坦无特征地形仍是视频弱区(第一人称水平视角画面变化小),走有地标地形(badlands/树林/水域)最可靠;③ gemini-3-flash 比 16s 窗稍慢会积压滞后,可接受。
- **本次寻路丝滑回归收官**:spinFreeze 死锁(16c5a8c)+ 之前各项水域/yaw 修复 → 多次随机长途双通道(pathChart+gemini video)均无异常、端到端丝滑到达。

## 续7 (2026-06-16) — 回放工具修成可忠实复现 live (a44c3e5)
- ⚠️ 揭穿:上轮"丝滑验收"跑的是 AgentTest **超平坦**世界(无效)。改用一直在测的 **Mountains 存档**(真山地),bot 在水边 climb-out **完全不丝滑**(用户:一直试跳1格岸)。
- 用户指令:录制/回放工具就是为复现 live 而建,用不了先修工具 → 修 4 部分:
  - A/B: EnvelopeCell 加 state(全blockstate SNBT)+nbt(block entity);ReplayTool 全保真还原,v1档fallback。
  - C: envelope 采样 dy 加深到 -3(实心地板,自包含还原不穿地)。
  - D: replan(默认)忠实重跑——还原地形+TP起点+从header重建原goal+正常规划(A*确定性→精确复现live涌现bug)。
- 验:GameTest 109/109(含state/nbt round-trip);Mountains live replan回放 replay-0002 **精确复现水边churn同格(~1823,2738)**;v2档 state覆盖39242/39242。
- 真bug(task#42 待修):水边climb-out每道+1岸先bob-stall+挖岸数秒才脱困=慢且丑,规划层应优先平齐出水口(flaky waterClimbOutRouteArena 域,待de-flake)。

## 续8 (2026-06-16) — 水边 climb-out 源头修复:浮力门 (ef3e497)
- 用户「从源头修,规划不该给出这样的路径」。三层根因:canStandAt 把任意水深当可站立 + Move 不查浮力 + 水域cost多只对XZ-goal生效。教训(fbde0f7):别动全局canStandAt,在Move层加浮力门。
- 修:`WorldView.isFloatingWater`(水格+下方也水=深水浮着)+ StepUp/StepUp2/DiagonalAscend 从浮力水格一律 invalid → A* 走平齐 walk-out 或破岸挖到平齐(均可执行),不再产不可执行的 +1 stepUp churn。
- waterClimbOutRouteArena 更新:+1 出口结构性禁止,浮力 bot 无论 tax 走平齐(maxY≤wsurf)。
- 验:GameTest 109/109+45/45;Mountains live (1823) **沉底+bob-stall 6s+diagUp振荡 4.4s 全消失**,保持水面 y62 流过,dist 185→84(60s)收敛。
- 残留:① 深水下沉(diagDown 入深水未gate,更微妙=下降有时合法潜goal)② 终端不可达goal churn(本goal在山上radius3够不到)③ real-Walker arena flaky(并行批次共享BotConfig,待de-flake)。

## 续9 (2026-06-16) — 深水高石崖「潜底爬岸」虚构修 (0408b2e)
- 新鲜可达随机长途(Mountains (1697,2611)→(1730,3100) **到达**)在 z3022 深水岸出现 ~30s 真停顿(视频实时:岸边反复上岸失败/卡崖底死角/剧烈抖动)= task#44 在可达路径复现。
- 地形(mc.query 实测):bot 向 +z 游过 6 深水池(水面y62),撞 z3024 整面石崖(stone y60→65+),岸顶 crest y63 在水面上方+1,空背包。
- 真根因:**ef3e497 浮力门副作用**——岸顶在水面上够不到平齐口,A* 改选 `SwimAshoreBreak` 节点放 **y59 软泥**(escapeBreakCost 比 y63 石头便宜)→潜底破软泥岸;但浮力 bot 水下蹬不起 jump-mount=212 tick churn(视频:游出水面又钻回岸下挖泥)。首猜 stepUp-dive 打错(submerged-ascent 门 peak287 无改善)。
- 修 0408b2e 两道源头门(「浮力 bot 水下不能上爬」):
  - `WorldView.isSubmergedAscent`:StepUp/StepUp2/DiagUp 目的地仍全淹没→invalid(防 stepUp 潜底,defensive)。
  - `WorldView.isSubmergedFoot`=isWater(foot+2):**SwimAshoreBreak/SwimBankClimbBreak foot≥2低于水面→invalid(须贴水面 jump-mount)= 真正生效的修**。
- 验:GameTest 109/109+45/45(深水穿越 arena 无回归);live A/B z3022 **深沉 churn 消除**(水中 peak totStuck 252→43,bot 贴水面 walk+水面破岸,沿水线东移找口,翻坡到达 y84)。复现台:snapshot id=bank3022 + tp 1741 68 2986 + goto xz(1733,3090)。
- 残留:高石崖空背包**近处无出口**→沿水面东移找口仍 ~5-8s 间歇停顿(peak~162)。进一步丝滑=规划层 exit-finding 更快锁定东侧低/坡口(heavily-tuned 区慎动)。

### 续9 补 — 回归验证 + 瀑布水帘 deadlock(0408b2e 无辜)
- 0408b2e 后向 W ~480 格新长途验证:**水域穿越无回归**(深水岸深沉没复现)。
- 但 Mountains 暴露多个独立 churn 源:① 陡干山 stepUp ~15s(已知 dry-staircase 残留)② **山顶多级瀑布水帘 deadlock**:bot 在水下做 121 次 undW=true 的 stepUp/diagUp(浮力蹬不起,dY 缩不进)+ fallWater15 反复下坠,totStuck 冲 2292、围 x~1495 死循环不脱困。
- **归因:0408b2e 对瀑布无辜**——该处 swimAshore 出现 0 次,我的门要求「to+head 都水」严格条件,瀑布水柱破碎→门不 fire→A* 路径同改前。
- **更广修方向(未做,需谨慎 A/B + 动深水穿越精调平衡)**:门改「from 头淹没(isWater(from)&&isWater(from+1))就禁 ascending stepUp/diag」可同盖岸+瀑布。但瀑布是 3D 复杂 feature,禁水下 stepUp 后可能 no-path/绕行,未必修好,投机性较高。

### 续10 (2026-06-17) — z2744 descent 侧根治(4ecb972)+ 整程 replay 到达
- 用户坚持用 replay 验证(对的):`mc.debug.replay replay-0004 replan` 重跑原 journey 揭出**改前在 z2744 死锁不到达**(swimUp918/diagDown200,totStuck1154)。
- 根因不对称:Fall/FallIntoWater 早 gate「降落淹没水格」,DiagonalDescend/StepDown 没有→A* diagDown 把浮力 bot 路由进深水缝,浮起→swimUp↔diagDown 振荡。
- 修(镜像 Fall.java:39):diagDown/stepDown.valid() 加 `if(isWater(to)&&isWater(to.above()))return false`。
- **验:完整 replay-0004 确定性重跑——z2744 死锁1154→peak5、z3022 岸51、端到端到达(1732,83,3097)**;GameTest 109+45 绿;局部 A/B slotPEAK3。
- 残留 z3034 东侧出口:~20s sink(totStuck394 自恢复),机制=**A* 把 WALK 节点路由在 y55-58 池底**(非 descent move),浮力 bot swimUp↔floor-walk 振荡=535df12「A* 路由河床」残留。修方向(风险):gate Walk-while-submerged(破潜 goal 横移)或调 waterCellTax/submergedTax(heavily-tuned)。

### 续11 (2026-06-19) — 终点海湾 deepwater 潜底 churn 根治(1705097+4017fa1)+ LIVE 端到端到达
- 陡高山 churn(3f287cb/4305919/e33e6ab)修好后,残留终点海湾 deepwater 潜底 churn:bot 在 goal 前 ~9 格深湾(对岸 y64-74 岸坡)潜到 y57 反复挖沙坝打转不过岸(三通道确认)。
- 精确诊断(walker per-tick log):planner committed path = `…swimDown swimDown swimTraverseBreak`——**潜下2格 + 挖穿水下沙坝(y60 solid)**;浮力 bot 挖水下 solid 浮起离块卡死(pos 冻 60.00 数十 tick)→re-route→再潜→churn。`mc.debug.plan` chain 证从水面 reached=true 全程 y62-63 水面路可达。
- 修1 `1705097`:**SwimTraverseBreak surface-gate**——eval 开头 `if (w.isWater(from.offset(0,1,0))) return null`(头在水下=潜底挖,拒绝;本 move 是「水面 lip 凿穿」)。GT 109/109。
- 修2 `4017fa1`:**escalation 在水里也 arm**——撤 e33e6ab 的 `if(!p.isInWater())` land-gate。纠错:e33e6ab 写「depthPenalty 推 bot 下水」是反的,depthPenalty 给下潜加税=**抑制潜底**;潜底挖沙坝路已被修1 gate 掉。GT 109/109(50_scene flaky 重跑绿)。
- **LIVE 双跑(tp 1732,64,3428→goto 1730,3500):①全局旋钮 水面穿越上岸 ARRIVED;②reactive 默认旋钮=水边churn~20-25s→`anti-churn(water)→escalation ARMED`→z3433→3477 上岸 y65→z3498 goalReached=true ARRIVED,全程水面无潜底。** 复现台:tp 1732 64 3428 + goto xz(1730,3500)。
- 残留:① reactive escalation 起步 ~20-25s edge-churn(churn-window 检测延迟;真丝滑需 proactive/常开水域 escalation)② 游泳中 yaw-spin(视频「水面反复剧烈转向」)③ 翻山执行器陡面 stepUp stutter。
- **残留①试过 `CHURN_WINDOW_WATER=200`(水里 10s 窗口提前 arm)→ REVERTED**:GT 109/109 但 live 更糟,charge/back-off 频率翻倍(escapes 1→4 vs 1→2),bot 仍 churn ~45s 才过岸——arm 提前≠过岸提前,缩窗口只增扰动。真修需 proactive(进深水前预判抬高岸坡),非更快 reactive。

### 续12 (2026-06-19) — 渐进式寻路 active + PROACTIVE pinch escalation = 水岸 churn 真解
- 用户拍板「开渐进式寻路大改 / 删掉 deferred / 随时开始攻」。Plan agent 勘明:quick-start stub/水面 bee-line/eager precompute/horizon/soft-commit 早已是渐进雏形。
- **land bee-line(432b359)**:tryWaterBeeline 的干地孪生(贪心同-Y standable march),flag `pathfinderProgressive`。LIVE 验证=Mountains 太碎触发 0 次(quick-start 已覆盖平地起步),**关键否定发现:greedy coarse stub 只治平地起步冻结,治不了 pinch churn**(粗方向正指障碍、撞上就停)。
- **⭐PROACTIVE pinch escalation(2a3915e,默认开 af5bba4)= 真解**:`maybeArmPinchEscalation`(big-search adopt 点)——大搜回来 best-effort 且 commit 段 goal 进展 `<PINCH_MIN_PROGRESS(8)` 格 = planner 卡 pinch,**当场 arm 深搜 escalation**,不等执行器 churn ~20s 反应窗。健康段(horizon≈48格)永不触发。
- **为何赢过 faster-reactive**:reactive 让坏 pocket 段先提交+bot 先 churn 再 undo;proactive 在坏段提交那刻就 arm,下一搜直接出绕障路。
- **LIVE A/B(深湾 tp 1732,64,3428→goto 1730,3500)**:proactive arm @~6s(首 commit 倒退-36格)vs reactive 20-52s;**开阔水面视频转 NORMAL(起步 churn 消除)、ARRIVED ~54s vs reactive ~162s(~3×快)**。GT 109/109(flag ON 也验)。
- 残留:对岸岸坡/沙坝近处独立 pre-existing pinch(「沙丘横移/水下死角」);land bee-line 留作平地 biome 件。
