package com.chatgpt2api.imageapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 提示词市场单条预设。
 *
 * 与 web 端 `web/src/app/image/banana-prompts.ts` 的 `BananaPrompt` 字段语义对齐，
 * 但只保留 App 端真正使用的最小子集：
 *  - 没有 `referenceImageUrls` / `preview`：App 不展示样图，避免引入运行时图片下载与缓存；
 *  - 没有 `author` / `link` / `source`：本地静态常量来源固定，不需要标注外链；
 *  - 没有 `localizations`：常量直接以中文落库，需要英文场景再单独维护。
 *
 * 所有字段在构造时即应非空，调用方拿到 [PromptPreset] 后无需再做 null / blank 校验
 * （Requirement 8.5：仅使用本地静态常量，不发起任何网络请求）。
 */
data class PromptPreset(
    val id: String,
    val title: String,
    val prompt: String,
    /** 推荐使用的运行模式："generate" 或 "edit"。仅作 UI 标注与排序提示，不强制限制 Composer 模式。 */
    val mode: String,
)

/**
 * 提示词市场分类。`presets` 顺序即为 UI 展示顺序，运行时不再排序。
 */
data class PromptCategory(
    val id: String,
    val label: String,
    val presets: List<PromptPreset>,
)

/**
 * 提示词市场全量预置数据。
 *
 * 数据来源：基于公开的
 * [banana-prompt-quicker](https://github.com/glidea/banana-prompt-quicker)
 * 与
 * [awesome-gpt-image-2-prompts](https://github.com/EvoLinkAI/awesome-gpt-image-2-prompts)
 * 项目抽取的代表性提示词，按主题重新整理为本地常量，统一为中文短描述（30~80 字），
 * 与 App 杂志风视觉基调匹配。
 *
 * Requirement 8.1 / 8.5 / 8.7：
 *  - 仅以 Kotlin 顶层常量形式持有，禁止运行时下载；
 *  - 后续如需扩充，直接在此文件追加分类或 preset，不引入抓取逻辑；
 *  - 设计上保证至少存在一条分类与一条 preset，使 [PromptMarketSheet] 永远有内容可展示。
 */
val PROMPT_MARKET_CATEGORIES: List<PromptCategory> = listOf(
    PromptCategory(
        id = "portrait",
        label = "肖像",
        presets = listOf(
            PromptPreset(
                id = "portrait-magazine-cover",
                title = "杂志封面写真",
                prompt = "时尚杂志封面级写真，柔和顶光均匀打亮面部，朱红腰封点缀，灰白纸面背景，85mm 中焦镜头，肤色干净自然。",
                mode = "generate",
            ),
            PromptPreset(
                id = "portrait-studio-rembrandt",
                title = "影棚伦勃朗光",
                prompt = "影棚黑色背景半身肖像，伦勃朗布光，主体居中正脸略侧，轻微胶片颗粒，整体偏暖调，皮肤纹理细节完整保留。",
                mode = "generate",
            ),
            PromptPreset(
                id = "portrait-cinematic",
                title = "电影感半身像",
                prompt = "电影感半身肖像，35mm 浅景深，城市霓虹光在脸颊上反射，暖橙与冷蓝对比，氛围安静克制，构图留白。",
                mode = "generate",
            ),
        ),
    ),
    PromptCategory(
        id = "illustration",
        label = "插画",
        presets = listOf(
            PromptPreset(
                id = "illustration-watercolor",
                title = "水彩绘本风",
                prompt = "水彩绘本风插画，柔和湿染笔触，米白纸面留白呼吸，温暖低饱和色调，构图简洁，主体单一，画面具有童话故事感。",
                mode = "generate",
            ),
            PromptPreset(
                id = "illustration-flat-vector",
                title = "极简扁平矢量",
                prompt = "极简扁平矢量插画，几何形体堆叠，三色色板内统一，居中构图留白，轻量斜向阴影，整体干净现代。",
                mode = "generate",
            ),
            PromptPreset(
                id = "illustration-ink-line",
                title = "单线水墨速写",
                prompt = "单线水墨速写风格，写意笔触干净利落，纸面纹理可见，主体轮廓清晰，留大量空白，朱砂红印章作点缀。",
                mode = "generate",
            ),
        ),
    ),
    PromptCategory(
        id = "product",
        label = "产品",
        presets = listOf(
            PromptPreset(
                id = "product-clean-studio",
                title = "极简产品白底",
                prompt = "极简产品白底图，柔光顶照，淡淡软阴影自然落地，中焦镜头主体居中，反光表面通透干净，无任何装饰元素。",
                mode = "generate",
            ),
            PromptPreset(
                id = "product-lifestyle",
                title = "生活场景产品",
                prompt = "生活场景产品图，自然窗光从左侧斜进，木质桌面，绿植背景轻度虚化，整体奶油暖色调，构图舒适松弛。",
                mode = "generate",
            ),
            PromptPreset(
                id = "product-magazine-spread",
                title = "杂志跨页商品图",
                prompt = "杂志跨页商品图，主体抢眼，朱红色块作版式构图，纸面纹理底，大幅留白用于排版文字，整体高级杂志风。",
                mode = "generate",
            ),
        ),
    ),
    PromptCategory(
        id = "scenery",
        label = "场景",
        presets = listOf(
            PromptPreset(
                id = "scenery-misty-mountain",
                title = "晨雾远山",
                prompt = "晨雾笼罩的远山层叠，薄雾压低天际线，画面以灰青为主调，前景松枝剪影点缀，整体宁静辽远，留白克制。",
                mode = "generate",
            ),
            PromptPreset(
                id = "scenery-coastal-dawn",
                title = "海岸破晓",
                prompt = "海岸破晓时刻，潮湿沙滩反射粉橙天光，远处灯塔剪影，几只海鸥在天际，整体安静诗意，宽幅构图。",
                mode = "generate",
            ),
            PromptPreset(
                id = "scenery-urban-night",
                title = "雨后城市夜景",
                prompt = "雨后城市夜景街景，霓虹倒映在湿润路面，行人剪影位于前景，长焦压缩感强，整体走电影色调，深暖与冷蓝对比。",
                mode = "generate",
            ),
        ),
    ),
    PromptCategory(
        id = "edit",
        label = "编辑",
        presets = listOf(
            PromptPreset(
                id = "edit-replace-paper-bg",
                title = "替换为纸面背景",
                prompt = "保留主体与构图不变，把背景替换为米色纸面纹理，加入轻微纸张折痕与暗角，整体匹配杂志风排版底图。",
                mode = "edit",
            ),
            PromptPreset(
                id = "edit-watercolor-style",
                title = "整体转水彩绘本",
                prompt = "保留主体构图，把整张图片转为水彩绘本风，柔化边缘笔触，加入纸面纹理与温润色彩，去掉锐利数字感。",
                mode = "edit",
            ),
            PromptPreset(
                id = "edit-magazine-grading",
                title = "杂志级色彩调整",
                prompt = "保留构图与主体，做一次杂志级色彩调整：朱红高光、油墨黑阴影、奶油黄中间调，整体收敛干净，去除杂色。",
                mode = "edit",
            ),
        ),
    ),
)

/**
 * 提示词市场底部弹层（Requirement 8.2 / 8.3）。
 *
 * 视觉沿用 [Glass] token：
 *  - 顶部 eyebrow + 主标题，呼应其它 Sheet 的 [SheetHeader]；
 *  - 横向分类 chip 行，选中态用 [Glass.Ink] 实心填充以呼应"主按钮 = 油墨黑"；
 *  - 卡片列表使用 [Glass.SurfaceMuted] + [Glass.GlassBorder]，与 ParamsSheet 等卡片保持一致。
 *
 * 用户点击任一 preset 时：先回调 [onPick] 写入 prompt，再触发 [onDismiss] 关闭 Sheet。
 * 选中状态不持久化到 ViewModel，每次重开默认从第一个分类开始浏览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptMarketSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategoryId by remember { mutableStateOf(PROMPT_MARKET_CATEGORIES.first().id) }
    val activeCategory = PROMPT_MARKET_CATEGORIES.firstOrNull { it.id == selectedCategoryId }
        ?: PROMPT_MARKET_CATEGORIES.first()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Glass.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Header：与其它 Sheet 一致的 eyebrow + 主标题排版。
            Text(
                "MARKET",
                fontSize = 9.sp,
                color = Glass.TextSecondary,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .height(2.dp)
                    .background(Glass.Accent),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "提示词市场",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Glass.TextPrimary,
            )
            Spacer(Modifier.height(14.dp))

            // 分类 chip：横向滚动，避免分类增多时被截断。
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                items(PROMPT_MARKET_CATEGORIES, key = { it.id }) { category ->
                    val active = category.id == selectedCategoryId
                    val chipShape = RoundedCornerShape(50)
                    Box(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(if (active) Glass.Ink else Glass.Surface)
                            .border(
                                width = 1.dp,
                                color = if (active) Glass.Ink else Glass.GlassBorder,
                                shape = chipShape,
                            )
                            .clickable { selectedCategoryId = category.id }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            category.label,
                            color = if (active) Color.White else Glass.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // 预设卡片：用 LazyColumn 控制最大高度，避免内容超出屏幕导致 Sheet 不可滚动。
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            ) {
                items(activeCategory.presets, key = { it.id }) { preset ->
                    val cardShape = RoundedCornerShape(14.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(Glass.SurfaceMuted)
                            .border(1.dp, Glass.GlassBorder, cardShape)
                            .clickable {
                                onPick(preset.prompt)
                                onDismiss()
                            }
                            .padding(14.dp),
                    ) {
                        Text(
                            preset.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Glass.TextPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            preset.prompt,
                            fontSize = 12.sp,
                            color = Glass.TextSecondary,
                            lineHeight = 18.sp,
                            maxLines = 4,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
