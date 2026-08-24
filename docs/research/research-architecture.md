---
id: research-architecture
title: kobweb-ssr — архитектурный ресёрч
type: research
status: active
date: 2026-08-24
---

# Ресёрч: SSR для Kobweb — go/no-go

Kobweb — фреймворк сайтов на Compose HTML: страницы пишутся как `@Composable`-функции, KSP
собирает таблицу маршрутов, Gradle-плагин генерирует точку входа, свой Ktor-сервер отдаёт статику
и API. SSR у него нет: страницы отдаются пустым каркасом либо снапшотом, снятым на этапе сборки, а
на клиенте дерево строится заново с нуля. Задача ресёрча — решить, делать ли SSR самому, и если
да, то каким из трёх путей: Node-сайдкар, свой applier поверх `compose-runtime`, или подождать
JVM-таргет `compose-html` от JetBrains и написать только Kobweb-специфику.

Документ разделяет **проверенные факты** (прочитанное в коде, в опубликованных артефактах, в
YouTrack), **принятые решения** и **риски**. Всё непроверенное названо гипотезой и говорит, где
будет проверено.

Дата съёма всех фактов — **2026-08-24**. Версии, к которым они привязаны: Compose Multiplatform
**1.9.3**, Kobweb — `master` на коммите `Update Kobweb CLI version in the README to v0.9.22`
(2026-08-19), Kilua — `main` на `616b7a3` (2026-08-17).

---

## 1. Проверенные факты

### 1.1 Апстрим: насколько близко JVM-таргет compose-html

Блок бинарный: если JVM-таргет реально едет в ближайшие два квартала, свой рендерер писать нельзя.

| Факт | Где проверено |
|---|---|
| Августовский пост JetBrains про SSR для Compose HTML — исследование, а не обязательство: «represents an exploration instead of an official commitment», «From this point onward is pure speculation» | [blog.jetbrains.com/kotlin/2026/08/exploring-compose-html-for-server-side-rendering/](https://blog.jetbrains.com/kotlin/2026/08/exploring-compose-html-for-server-side-rendering/) |
| Предложенная сигнатура — `fun renderToString(content: @Composable DOMScope<DomElement>.() -> Unit): String`; один проход композиции, без рекомпозиции и эффектов, слушатели принимаются, но инертны | там же |
| Гидратация в посте прямо вынесена за скобки: «Hydration and state sync are the natural next question, not an answer» | там же |
| JetBrains уже говорят с мейнтейнерами Kobweb, Kilua, Summon и с командой Spring; обсуждение — в канале `#compose-ssr` в kotlinlang Slack | там же |
| CMP-6758 «Support Server-Side Rendering» — Answered/Resolved 31.10.2024, ассигни нет. Ответ Victor Kropp: «We haven't evaluated this option yet, as we are focusing on different targets at the moment» | `youtrack.jetbrains.com/api/issues/CMP-6758` |
| **Задачи на JVM-таргет compose-html в YouTrack нет.** Поиск `compose-html` по всем проектам (30 результатов) не вернул ни одной; выборка задач CMP, созданных после 2026-06-01 (40 штук), — тоже | `youtrack.jetbrains.com/api/issues?query=compose-html`; `?query=project: CMP created: 2026-06-01 .. 2026-12-31 html` |
| CMP-4461 «Add wasmJs support to Compose HTML» — **Open, Unassigned, создана 10.03.2024**. CMP-5888 «Compose HTML Kotlin/Wasm target support» — Submitted, Unassigned, 05.08.2024. CMP-8151 «[Compose HTML] Decouple compose-ui from…» — Open, Unassigned | там же |
| В `JetBrains/compose-multiplatform` и `JetBrains/compose-multiplatform-core` нет ни одного вхождения `renderToString`. Из 1025 веток `compose-multiplatform-core` ни одна не про SSR (ближайшие по имени — `feat/html-interop`, `remove_jsJvm_check_in_Stability`) | `gh search code renderToString --owner JetBrains`; `gh api repos/JetBrains/compose-multiplatform-core/branches` |
| Планы по вебу на 2026 — про canvas-таргет (drag-and-drop, ввод и отрисовка текста, интероп с содержимым HTML-страницы), про compose-html там нет ничего | [blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/) |

**Следствие 1. JVM-таргета в ближайшие два квартала не будет, и критерий брифа снят: свой рендерер
апстримом не запрещён.** Решающая улика тут не отсутствие тикета, а CMP-4461. wasmJs для
compose-html — строго более простая работа, чем JVM: в Kotlin/Wasm есть привязки к `org.w3c.dom`,
то есть менять сигнатуры не надо вообще, надо переписать интринсики. Эта задача открыта и не
назначена **два с половиной года**. У JVM-таргета, который требует ещё и смены публичных
сигнатур, тикета нет ни одного.

**Следствие 2. За постом не стоит выделенный человек — по крайней мере в YouTrack его нет.** Ни
одной задачи по compose-html не назначено; те, что есть, висят на Oleksandr Karpovich вместе с
десятком задач по canvas-вебу. Осторожность формулировок поста — не редакторская манера, а
точное описание статуса.

**Следствие 3. Обратное тоже верно: тема живая, и первым JetBrains выкатят самую лёгкую половину.**
`renderToString` без рекомпозиции, без эффектов и с инертными слушателями — это тот кусок, который
дешевле всего написать и который ничего не решает: он не даёт ни гидратации, ни переноса
состояния, ни CSS. Строить план на том, что этот кусок не приедет, — нельзя. Строить план на том,
что приедет и всё закроет, — тоже.

### 1.2 Внутренности compose-html: подменяется ли applier

Второй бинарный блок. Формулировка брифа — «можно ли подсунуть свой Applier через публичный
API» — оказалась не тем вопросом; ответ на неё «да», и он ничего не даёт.

| Факт | Где проверено |
|---|---|
| `html/core/src` содержит **только** `jsMain` и `jsTest` | `JetBrains/compose-multiplatform`, `html/core/src` |
| При этом `jvm()` в таргетах объявлен, и **опубликованный `html-core-jvm:1.9.3` существует и пуст**: jar на 599 байт, четыре записи, ни одного класса | `repo1.maven.org/maven2/org/jetbrains/compose/html/html-core-jvm/1.9.3/html-core-jvm-1.9.3.jar`; `html-core-1.9.3.module` (варианты `jvmApiElements-published`, `jvmRuntimeElements-published`) |
| `DomApplier` — `@ComposeWebInternalApi class`, то есть **не `internal`**, а `@RequiresOptIn`; финальный, `AbstractApplier<DomNodeWrapper>` | `html/internal-html-core-runtime/src/jsMain/kotlin/org/jetbrains/compose/web/internal/runtime/DomApplier.kt` |
| `DomNodeWrapper` — `open class`, но его `node` типизирован `org.w3c.dom.Node` | там же |
| `ElementBuilder` — `fun interface ElementBuilder<TElement : Element> { fun create(): TElement }`, публичный и реализуемый; реализация по умолчанию `ElementBuilderImplementation` (private) зовёт `document.createElement(tagName)` и `el.cloneNode()` | `html/core/src/jsMain/kotlin/org/jetbrains/compose/web/dom/Elements.kt:68` |
| `renderComposable` зашивает `DomApplier(DomNodeWrapper(root))`, перегрузки с чужим `Applier` нет | `html/internal-html-core-runtime/src/jsMain/kotlin/org/jetbrains/compose/web/renderComposable.kt` |
| **Но** `ControlledComposition(applier, parent)` — публичный API `compose-runtime`, так что композицию со своим аппликером собрать можно, минуя `renderComposable` | тот же файл, строка сборки композиции |
| **И это ничего не даёт.** `TagElement` создаёт `private class DomElementWrapper`, а атрибуты, инлайн-стили, классы и слушатели пишет в блоке `update { }` вызовами `DomElementWrapper::updateAttrs`, `::updateStyleDeclarations`, `::updateClasses`, `::updateEventListeners` — то есть **прямо в `org.w3c.dom.Element`**. Через аппликер проходят только `insert` / `remove` / `move` | `html/core/src/jsMain/kotlin/org/jetbrains/compose/web/dom/Base.kt` |
| Kotlin/JS-интринсики в `html/core`: 94 `unsafeCast`, 14 `asDynamic`, 14 `external interface`, 2 `js(...)`, 1 `external class` | `grep` по `html/core/src/jsMain` |
| 27 из 65 файлов `jsMain` импортируют `org.w3c` (≈42 %); всего в модуле 528 объявлений верхнего уровня | там же |
| `AttrsScope<out TElement : Element>`, `ElementScope<out TElement : Element>`, `DOMScope<out TElement : Element>` параметризованы `org.w3c.dom.Element`; `DisposableRefEffect` и `DomEffectScope.onDispose` принимают его же | `attributes/AttrsScope.kt`, `dom/ElementScope.kt`, `internal-html-core-runtime/.../DOMSCope.kt` |
| `StyleScope` — **чистый**: `org.w3c` не импортирует, оперирует своими `StylePropertyValue` | `css/StyleScope.kt` |
| Дампа бинарной совместимости у модуля нет: ни одного `*.api` в `html/` | `find html -name '*.api'` |

**Следствие 1. Пути «свой applier поверх compose-runtime» в формулировке брифа не существует.**
Аппликер подменяется, но точка мутации — не он. Свой `Applier<DomNodeWrapper>` увидит структуру
дерева и не увидит ни одного атрибута, класса, стиля и слушателя: они уже записаны в браузерный
узел мимо него. Это не ограничение видимости, которое обходится `@OptIn`, — это архитектура:
`compose-html` не хранит модель узла в Kotlin, он хранит её в DOM.

**Следствие 2. Значит остаётся форк `html/core` целиком.** 528 объявлений, отсутствие api-дампа,
никакой гарантии бинарной совместимости → это не разовая операция, а вечный ребейз. Ровно поэтому
Kilua — не форк compose-html, а отдельный фреймворк (см. §1.3).

**Следствие 3. Есть третий вариант, и он не в списке брифа: не подменять DOM, а подсунуть его.**
`compose-html` требует от среды немногого — `document.createElement`, `cloneNode`,
`appendChild`/`insertBefore`/`removeChild`, `setAttribute`, `classList`, `style`,
`addEventListener` и сериализацию поддерева. Всё это реализовано в DOM-эмуляциях для Node и, тем
более, в настоящем headless-браузере. Тогда `compose-html` не трогается вообще — ни форка, ни
аппликера, ни опт-инов.

**Следствие 4, отдельно: пустой `html-core-jvm` — тихая ловушка.** Зависимость на `html-core` из
JVM-исходника **резолвится** и не даёт ни одного символа. Ошибка будет не «не найдена
зависимость», а «unresolved reference» на каждую функцию. И обратное: «добавить JVM-таргет» для
JetBrains — это не настройка Gradle, она уже сделана и опубликована; это написать `jvmMain`
целиком, поменяв публичные сигнатуры трёх скоупов.

### 1.3 Prior art: как решены три конкретные задачи

#### Композиция → HTML вне браузера

| Факт | Где проверено |
|---|---|
| **Kilua не эмулирует DOM.** У неё переключатель `RenderConfig`: `DomRenderConfig` / `StringRenderConfig`, и `isDom` протаскивается в каждый компонент | `kilua/src/commonMain/kotlin/dev/kilua/core/RenderConfig.kt` |
| Каждый компонент умеет сериализовать себя сам: `Component.renderToStringBuilder(builder)`, `Component.innerHTML` обходит детей | `kilua/src/commonMain/kotlin/dev/kilua/core/Component.kt:88` |
| `Tag` **хранит модель узла в Kotlin**: `tagAttrs.attributesMap`, `tagStyle.stylesMap`, `propertyValues`, `className` — и `renderToStringBuilder` собирает `<tag class=… id=… attrs… style=…>` из них | `kilua/src/commonMain/kotlin/dev/kilua/html/Tag.kt:243` |
| При `!isDom` браузерный элемент не создаётся: `SafeDomFactory.createElement`, а обращение к `element` бросает «Can't use DOM element with the current render configuration» | `Tag.kt:113`, `Tag.kt:184` |
| Аппликер — `ComponentApplier : AbstractApplier<ComponentBase>` | `kilua/src/commonMain/kotlin/dev/kilua/compose/ComponentApplier.kt` |
| Серверное приложение — та же кодовая база, скомпилированная в **Kotlin/WasmJS**, запущенная в Node; поднимает свой HTTP-сервер на порту 7788 | `modules/kilua-ssr/src/commonMain/kotlin/dev/kilua/ssr/GlobalSsrRouter.kt`, `NodeHttp.kt` |
| Ни `jsdom`, ни `happy-dom`, ни `linkedom`, ни `domino` в репозитории Kilua не упоминаются | `grep -ri` по всему репозиторию |
| **Summon не использует `compose-runtime`.** У него своя `annotation class Composable` в `codes.yousef.summon.annotation`; в зависимостях `commonMain` — `kotlinx.html`, `kotlinx.serialization`, `coroutines`, `atomicfu`, и ни одного артефакта Compose | `codeyousef/summon`, `summon-core/build.gradle.kts`, `summon-core/src/commonMain/kotlin/codes/yousef/summon/annotation/Composable.kt` |
| «Streaming SSR» у Summon — это `kotlinx.html` `appendHTML` / `createHTML` | `summon-core/src/commonMain/kotlin/codes/yousef/summon/ssr/StreamingSSR.kt`, `summon-core/src/jvmMain/.../ktor/KtorStreamingSupport.kt` |
| Summon: 164 звезды, 2 форка, Apache-2.0, последний push 20.08.2026 | `gh api repos/codeyousef/summon` |

**Следствие 1. Kilua решила ровно ту задачу, которую `compose-html` решить не может, и решила её
единственным способом — сохранив модель узла в Kotlin.** Это и есть разделительная линия между
двумя библиотеками, и она проходит не по «Wasm против JS», а по тому, где живут атрибуты. Пока
`compose-html` пишет их в `org.w3c.dom.Element`, «свой рендерер поверх compose-html» невозможен
по построению.

**Следствие 2. Summon в этом сравнении не участник.** «Элегантность Jetpack Compose» у него — по
внешнему сходству, а не по рантайму: композиции нет, есть шаблонизатор с рекурсивным обходом. Его
опыт про SSR не переносится ни на что, что использует `compose-runtime`. Это не упрёк библиотеке,
это ответ на вопрос брифа «как реализован заявленный streaming» — `kotlinx.html`, посимвольно.

#### Извлечение CSS без CSSOM

| Факт | Где проверено |
|---|---|
| Kilua: конфигурация Webpack в проде подменяет импорты CSS на `false`, стили в клиентский бандл не попадают; свои CSS-файлы регистрируются через `CssRegister.register()`; сервер вклеивает их в документ перед отдачей | [kilua.dev/development-guide/server-side-rendering](https://kilua.dev/development-guide/server-side-rendering) |
| **Kobweb уже решил эту задачу — через CSSOM.** `kobweb export` открывает страницу в Playwright и выполняет JS, который обходит `document.styleSheets`, и для каждого «пустого» `<style>`-узла записывает в `innerHTML` собранный из `cssRules[i].cssText` текст. Комментарий в коде объясняет зачем: иначе пользователь сначала увидит неоформленный текст, а стили «вползут» после загрузки JS | `varabyte/kobweb`, `tools/gradle-plugins/application/src/main/kotlin/com/varabyte/kobweb/gradle/application/tasks/KobwebExportTask.kt`, метод `Page.takeSnapshot` |
| Kinetica: описана на kotlinlang.org как фреймворк с серверным рендерингом и «minimal DOM patches», но **публичного репозитория под организацией Heapy на 2026-08-24 не нашлось** | [kotlinlang.org/docs/js-frameworks.html](https://kotlinlang.org/docs/js-frameworks.html); `gh api orgs/Heapy/repos` |

**Пробел, а не факт.** Заявленное у Kinetica извлечение CSS проверить не удалось: кода нет.
Документ этого не покрывает. Не переносить в решение ничего, что опирается на Kinetica.

**Следствие.** Задача «извлечь CSS без CSSOM» для Kobweb не стоит: у Silk стили строятся
императивно в CSSOM, и единственный существующий способ их достать — спросить CSSOM. Kobweb это
уже делает, механизм готов и его можно переиспользовать дословно. Это, в свою очередь, сильный
довод за рендерер-браузер, а не рендерер-Node (см. D2).

#### Гидратация без пересоздания дерева

| Факт | Где проверено |
|---|---|
| Kilua честно называет свою гидратацию заменой содержимого: «The 'hydration' is implemented in a simple way (by replacing the rendered content)»; сложные JS-компоненты приезжают с сервера плейсхолдерами; аутентифицированный контент не поддерживается | [kilua.dev/development-guide/server-side-rendering](https://kilua.dev/development-guide/server-side-rendering) |
| **Kobweb делает то же самое, и это видно в текущем коде, а не только в тикете 2022 года.** Генерируемый `main.kt` выполняет `val root = document.getElementById("_kobweb-root")!!` и `while (root.firstChild != null) { root.removeChild(root.firstChild!!) }`, и только потом `renderComposable`. Комментарий рядом: «Think of this as poor man's hydration :)» | `tools/gradle-plugins/application/src/main/kotlin/com/varabyte/kobweb/gradle/application/templates/MainTemplate.kt` |
| Мейнтейнер Kobweb в issue #113 объясняет, почему упёрлись, цитируя Jake Wharton: Compose держит ссылки на узлы дерева **и** локальное состояние композиций, и восстановить надо и то и другое, иначе дерево строится заново. И добавляет: «It's honestly not that bad of an experience as far as I can tell» | [varabyte/kobweb#113](https://github.com/varabyte/kobweb/issues/113) |

**Следствие. Упёрлись все и в одно и то же: слот-таблица композиции не сериализуема.** Это не
недоработка Kilua и не лень Kobweb — это свойство `compose-runtime`. Любая «настоящая» гидратация
требует либо восстановить слот-таблицу, либо не восстанавливать её вовсе, а прогнать композицию
заново и **сопоставить** её результат с уже стоящим DOM вместо того, чтобы его снести.

#### За пределами Kotlin

| Факт | Где проверено |
|---|---|
| Blazor: четыре режима — Static Server, Interactive Server, Interactive WebAssembly, Interactive Auto; режим назначается директивой `@rendermode` **на экземпляре компонента или на его определении**; предварительный рендер включён для интерактивных режимов по умолчанию | [learn.microsoft.com/aspnet/core/blazor/components/render-modes](https://learn.microsoft.com/en-us/aspnet/core/blazor/components/render-modes) |
| Blazor: «Parameters passed to an interactive child component from a Static parent **must be JSON serializable**. This means that you can't pass render fragments or child content from a Static parent component to an interactive child component» | там же |
| Blazor: компонент может во время выполнения узнать, где он исполняется и интерактивен ли он — `RendererInfo.Name` (`Static` / `Server` / `WebAssembly` / `WebView`), `RendererInfo.IsInteractive`, `AssignedRenderMode` | там же |
| Qwik: слушатели сериализуются прямо в разметку — `<button on:click="./chunk.js#handler_symbol">`; Qwikloader ставит один глобальный слушатель; границы компонентов тоже сериализуются в HTML | [qwik.dev/docs/concepts/resumable](https://qwik.dev/docs/concepts/resumable/) |
| Qwik: замыкания сериализуемы, **только если обёрнуты в QRL**, и делает это оптимизатор (компиляторный проход). Не сериализуются классы (`instanceof`, прототипы) и стримы | там же |
| `androidx.compose.runtime.saveable` опубликован для jvm (desktop), js(ir), androidJvm и native — то есть `SaveableStateRegistry` доступен и на сервере, и в браузере | `repo1.maven.org/.../runtime-saveable/1.9.3/runtime-saveable-1.9.3.module` |

**Следствие 1 (Blazor). Граница «серверное / интерактивное» должна быть в типах, и через неё
должно проходить только сериализуемое.** Blazor выясняет это в рантайме и падает; но сама
конструкция — «режим объявляется на компоненте, а не на приложении» — переносится один в один и
стоит того, чтобы её скопировать. В терминах Compose это значит: `@Composable`-лямбда контента
границу не пересекает, и это надо запретить на уровне API, а не обнаружить на проде.

**Следствие 2 (Qwik) — и это отклонение от посылки брифа.** Resumability вместо гидратации для
Compose **не** короче гидратации. Несущая часть Qwik — это оптимизатор, который режет замыкания на
лениво подгружаемые чанки со стабильными именами символов и сериализует их захваченное окружение.
У Compose такого прохода нет и не предвидится: обработчики событий и тела `remember { }` — обычные
котлиновские замыкания, у которых нет ни стабильного адреса, ни сериализуемого окружения. Строить
на этом первую версию — значит начать с написания компиляторного плагина.

**Но половина идеи применима без компилятора, и она уже реализована в Compose.**
`SaveableStateRegistry` / `rememberSaveable` — это ровно «сериализовать *объявленное* состояние и
восстановить его на другой стороне». Kilua делает то же самое руками через `stateSerializer` /
`getSsrState`, потому что у неё этого примитива нет. У нас он есть, на обеих сторонах, в
опубликованном артефакте. Это самая дешёвая половина resumability и самый интересный аргумент для
`#compose-ssr` — но подавать его надо как «состояние, а не замыкания», иначе разговор уедет в
компиляторный плагин.

**Следствие 3 (Solid / Marko) — посылка брифа не подтвердилась.** Утверждение «Solid и Marko ловят
hydration mismatch на уровне компилятора» проверить не удалось: найденное про компилятор Marko
касается partial hydration и resumability, то есть **устранения** гидратации, а не обнаружения
расхождения; в Solid расхождение — рантайм-ошибка. Считать непроверенным. Адрес проверки — M1,
и до тех пор не опираться на это ни в одном решении.

### 1.4 Kobweb: точки расширения и что уже написано

| Факт | Где проверено |
|---|---|
| **Форк сервера не нужен.** Есть публичный интерфейс `KobwebServerPlugin { fun configure(application: Application) }` — на вход отдают сырой Ktor `Application` | `backend/server-plugin/src/main/kotlin/com/varabyte/kobweb/server/plugin/KobwebServerPlugin.kt` |
| Плагины грузятся `ServiceLoader.load(KobwebServerPlugin::class.java, pluginClassloader)` и конфигурируются **после** `configureRouting` / `configureSerialization` / `configureHTTP` | `backend/server/src/main/kotlin/com/varabyte/kobweb/server/Application.kt:136` |
| Страницы отдаёт catch-all `get("$basePath/{$KOBWEB_PARAMS...}")`; в статической раскладке каждый экспортированный файл дополнительно регистрируется точным маршрутом | `backend/server/src/main/kotlin/com/varabyte/kobweb/server/plugins/Routing.kt` |
| Экспорт — Playwright; браузер выбирается (`Chromium` по умолчанию, есть `Edge`, `Firefox`, `WebKit`); страница открывается с `?_kobwebIsExporting=true&_kobwebColorModeStrategy=BOTH`; на выходе `Jsoup.parse(content())` | `tools/gradle-plugins/application/src/main/kotlin/.../KobwebExportTask.kt` |
| Таблица маршрутов **уже есть на JVM**: KSP выдаёт `@Serializable class FrontendData` (страницы, лэйауты, `cssStyles`, `cssStyleVariants`, `keyframes`, инициализаторы), Gradle-плагин генерирует из неё `main.kt` с `ctx.router.register(...)` | `tools/processor-common/src/main/kotlin/com/varabyte/kobweb/project/frontend/FrontendData.kt`; `tools/ksp/site-processors/src/main/kotlin/com/varabyte/kobweb/ksp/frontend/FrontendProcessor.kt`; `.../templates/MainTemplate.kt` |
| #114 «Add server side rendering» — open с 01.02.2022, milestone 1.1, ноль комментариев, в теле: «This bug is useless if #113 isn't solved first» | [varabyte/kobweb#114](https://github.com/varabyte/kobweb/issues/114) |
| #22 «Epic: Allow Kobweb to work in cases where it doesn't own the server» — мейнтейнер предлагает прокси и подробно объясняет, почему live reload для чужого сервера «probably won't ever happen» (класслоадерные трюки в `ApiJarFile.kt`) | [varabyte/kobweb#22](https://github.com/varabyte/kobweb/issues/22) |
| Bus factor: из последних 200 коммитов 177 — David Herman (bitspittle), 19 — Dennis Tsar, остальные единичные. Проект активен: 60 коммитов в августе 2026, 40 в июле, 60 в июне | `git log` по `varabyte/kobweb`, глубина 200 |

**Следствие 1. Швы для SSR в Kobweb уже прорезаны, и мейнтейнер их прорезал не под SSR.**
`KobwebServerPlugin` даёт полный Ktor `Application` — можно поставить свои маршруты, перехватчики,
плагины, не трогая ни строчки апстрима. Это снимает самый дорогой риск всей затеи: зависимость от
темпа одного человека.

**Следствие 2. Позиция мейнтейнера по SSR — не отказ, а очерёдность.** Отказов с аргументацией не
нашлось; нашлась зависимость #114 → #113 и честное «poor man's hydration :)» прямо в генераторе
кода. То есть предметное предложение не встретит «мы этого не хотим», оно встретит «сначала
гидратация».

**Следствие 3. «Что мешает KSP положить таблицу маршрутов в common» — вопрос задан не к тому
месту.** KSP уже кладёт её в JVM: `FrontendData` — `@Serializable` и живёт в `tools/processor-common`,
то есть в JVM-модуле Gradle-плагина. Мешает не генератор, а то, что сами страницы —
`@Composable`-функции, вызывающие Silk и `compose-html`, то есть код, который существует только в
JS-исходниках. Таблица маршрутов переезжает в common ровно тогда, когда туда переезжает
`compose-html`, и ни минутой раньше. Для SSR это означает: **имена и пути маршрутов на сервере
доступны уже сегодня, тела страниц — нет.**

**Следствие 4. Bus factor равен единице.** Это довод не «не связываться», а «не класть свою работу
в апстрим». Всё, что делается снаружи через `KobwebServerPlugin` и свой Gradle-плагин, переживёт
паузу в апстриме; всё, что делается патчами в Kobweb, — не переживёт.

### 1.5 Экономика

| Факт | Где проверено |
|---|---|
| **Kilua держит ровно один Node-процесс на инстанс** — `private var nodeJsProcess: Process?`, запуск `ProcessBuilder("node", "main.bundle.js")` | `modules/kilua-ssr-server/src/jvmMain/kotlin/dev/kilua/ssr/SsrEngine.kt` |
| **Рендер внутри этого процесса сериализован спин-локом**: `while (lock) { delay(1.milliseconds) }; lock = true` вокруг обработки каждого запроса | `modules/kilua-ssr/src/commonMain/kotlin/dev/kilua/ssr/GlobalSsrRouter.kt` |
| Нагрузку держит кэш: `ExpiringMap` с `cacheTime` по умолчанию **10 минут** | `SsrEngine.kt`, `DEFAULT_SSR_CACHE_TIME` |
| Замеров памяти и задержек у Kilua нет: в документации по SSR числа отсутствуют вовсе | [kilua.dev/development-guide/server-side-rendering](https://kilua.dev/development-guide/server-side-rendering) |
| Kotlin Foundation Grants: приём заявок **закрыт**, даты следующего раунда не опубликованы. Критерии включают «Use a permissive open-source license and be publicly available» и «Demonstrate active maintenance and community engagement». Права на работу остаются у автора | [kotlinfoundation.org/grants](https://kotlinfoundation.org/grants/) |
| В `CONTRIBUTING.md` `compose-multiplatform` CLA не упомянут; требуется завести задачу в YouTrack до PR, PR в `master`, без merge-коммитов; основная кодовая база — в `compose-multiplatform-core` | [github.com/JetBrains/compose-multiplatform/blob/master/CONTRIBUTING.md](https://github.com/JetBrains/compose-multiplatform/blob/master/CONTRIBUTING.md) |

**Следствие 1. Пропускная способность инстанса Kilua равна `1 / latency` одного рендера, и это
не оптимизация, это архитектура.** Спин-лок стоит там не случайно: серверное приложение — это
**одна** композиция с **одним** роутером, и параллельно отрендерить два разных URL она не может в
принципе. Масштабирование у такой конструкции только горизонтальное, и кэш в ней несущий, а не
вспомогательный. Любой сайдкар-путь наследует это свойство: если состояние композиции глобально,
конкурентность равна единице на процесс.

**Следствие 2. Своих чисел нет ни у кого, и чужие сюда не переносятся.** Найденные публичные
замеры (p99 Next.js, эффект pointer compression на heap) сняты с React-фреймворков на V8 и говорят
про JS-объекты, а не про Kotlin/Wasm или про headless-Chromium. Числа в этот документ **не
переносятся**; они снимаются самостоятельно, и до тех пор ни одно решение на них не опирается.
Адрес: M1, стенд — локальный прогон демо Kilua и локальный прогон нашего прототипа на одной машине.

**Следствие 3. Грант в план не закладывать.** Приём закрыт, дат нет, а критерий «активное
сопровождение и вовлечённое сообщество» новорождённый проект не проходит по определению. Это
возможный источник денег на второй год, а не на первый.

**Следствие 4. Отсутствие упоминания CLA — не доказательство отсутствия CLA.** `CONTRIBUTING.md`
про него молчит, бота в `.github` не видно. Это ровно тот вопрос, который дешевле задать в
`#compose-ssr`, чем выяснять на середине готового PR. Внесён в черновик сообщения (§5).

---

## 2. Решения

### D1. Go — но не по одному из трёх путей брифа

Бриф: выбрать один из трёх — Node-сайдкар, свой applier поверх `compose-runtime`, ждать
JVM-таргет.

Решение: **делать. Путь — сайдкар, подключённый через существующий `KobwebServerPlugin`, с
рендерером за сменной границей. Своего applier не писать. `compose-html` не форкать. Апстрим не
ждать, но и не игнорировать.**

Таблица трёх путей — с четвёртой строкой, которая и выбрана:

| Путь | Трудозатраты | Риск быть съеденным апстримом | Что остаётся твоим в любом случае |
|---|---|---|---|
| **A. Node-сайдкар** — приложение целиком крутится в Node поверх DOM-эмуляции | Средние. Ноль изменений в `compose-html`. Основной риск — полнота эмуляции CSSOM (§3, Риск 2) | Низкий. Приедет `renderToString` — сайдкар заменяется, шов остаётся | Маршрутизация на сервере, кэш и его инвалидация, передача состояния, гидратация |
| **B. Свой applier поверх `compose-runtime`** | **Пути нет.** Аппликер подменяется, но атрибуты, стили, классы и слушатели пишутся мимо него (§1.2). Реально это форк `html/core`: 528 объявлений, без api-дампа, вечный ребейз | Высокий и бессмысленный: приедет JVM-таргет — весь форк в мусор | Ничего. Это и есть работа, которую JetBrains однажды сделают |
| **C. Ждать JVM-таргет** | Ноль сейчас, неизвестно сколько потом | Не применимо — это и есть ставка на апстрим | Ничего сейчас. И CMP-4461 висит открытой и неназначенной два с половиной года (§1.1) |
| **A′. Сайдкар с рендерером-браузером** *(выбрано)* | **Наименьшие.** Механизм снятия снапшота и запекания CSSOM уже написан в `KobwebExportTask` — переносится на запрос почти дословно | Низкий. Тот же шов, что у A | То же, что у A, плюс переиспользованный код экспорта |

Почему A′, а не A:

- механизм извлечения CSS — единственная часть задачи, которую нельзя обойти, — **у Kobweb уже
  написан и работает через CSSOM** (§1.3). В настоящем браузере он работает по определению; в
  DOM-эмуляции он работает настолько, насколько там реализован CSSOM, а это и есть главный
  неизвестный риск пути A;
- Playwright уже в зависимостях Gradle-плагина Kobweb, кэш браузера уже управляется
  (`KobwebBrowserCacheIdTask`), выбор браузера уже сконфигурирован. Ставить Node-обвязку — значит
  завести второй рантайм ради экономии, размер которой никто не измерил;
- цена A′ известна и плоха: процесс Chromium вместо процесса Node. Но она **известна**, а цена A
  включает неизвестное. Первую версию строим на известном, а Node меряем против неё как
  оптимизацию (M2), а не принимаем на веру.

Цена решения, честно: A′ отдаёт память. Ставка на то, что при кэше (Kilua держит 10 минут,
§1.5) и горизонтальном масштабировании память дешевле, чем незакрытый риск CSSOM. Проверяется
замером в M1; если замер покажет обратное, переключение на Node — это смена реализации за швом, а
не переписывание.

### D2. Рендерер — за границей, которую можно заменить, и это главное архитектурное требование

Всё, что зависит от способа получения HTML, живёт за одним интерфейсом: «URL и контекст запроса
на входе, HTML и сериализованное состояние на выходе». Реализаций планируется три: браузер
(M1), Node (M2, если замер оправдает), `renderToString` из апстрима (когда приедет).

Почему это не преждевременное обобщение: из §1.1 известно, что апстрим шевелится, но приедет
позже и закроет только половину. Шов здесь — не про красоту, а про то, чтобы приезд
`renderToString` был правкой одного модуля, а не поводом переписывать проект. Это и есть ответ на
вопрос «что остаётся твоим»: **всё остальное**.

### D3. Передача состояния — через `SaveableStateRegistry`, а не через свой сериализатор

Отвергнуто: собственный `stateSerializer` в духе Kilua. Причина — в Compose уже есть примитив
ровно для этого, опубликованный для JVM, JS и native (§1.3), и он вдобавок задаёт правильную
дисциплину: состояние объявляется сохраняемым явно, а не собирается ad hoc.

Цена: `rememberSaveable` покрывает только то, что автор страницы туда положил. Всё остальное на
клиенте пересчитается. Это осознанно: гарантировать перенос произвольного состояния нельзя (§1.3,
следствие про Qwik), а обещать это — значит обещать компиляторный плагин.

### D4. Граница «серверное / интерактивное» — в типах, по образцу Blazor

Режим объявляется на компоненте, а не на приложении, и через границу проходит только
сериализуемое: `@Composable`-лямбда контента её не пересекает. Blazor выяснил это в рантайме
(§1.3) — мы должны выяснять в компиляции.

Гипотеза о механизме: аннотация плюс проверка в KSP-процессоре, рядом с тем, что уже разбирает
`@Page`. Проверить в M2.

### D5. Гидратация — сопоставление, а не снос дерева *(отклонение от текущего поведения Kobweb)*

Kobweb сегодня сносит `_kobweb-root` целиком и рендерит заново (§1.3). При SSR это хуже, чем при
экспорте: страница, пришедшая с сервера уже с данными, мигнёт и перерисуется.

Решение: после первой композиции не звать `renderComposable` на пустой корень, а сопоставить
результат композиции с уже стоящим поддеревом. Это ровно то, что описано в #113 как «настоящая
гидратация».

**Это самый рискованный пункт плана,** потому что упирается в §1.2: сопоставлять нечем — аппликер
структуру видит, атрибуты нет. Открытый вопрос 2 в §3. Порядок работ построен так, чтобы D5 не
блокировал всё остальное: без него получается SSR с мерцанием, что уже лучше, чем ничего, и что
уже есть у Kilua.

### D6. Работу в апстрим не отдавать до тех пор, пока не будет ответа про CLA и про сроки

Не из скрытности, а из §1.5: CLA не описан, а грантовая программа закрыта. Пока непонятно, что
подписывается, вопрос обсуждается в `#compose-ssr` (§5), а код лежит в своём репозитории под
пермиссивной лицензией — что заодно удовлетворяет первому критерию Kotlin Foundation.

---

## 3. Риски и открытые вопросы

**Риск 1. Один процесс — один рендер.** Конкурентность серверной композиции равна единице
(§1.5), и это свойство архитектуры, а не реализации Kilua. Механизм смягчения: с первого дня
считать пул рендереров единицей масштабирования, а не оптимизацией, — то есть N процессов за
диспетчером с очередью, метрика глубины очереди и времени ожидания в ней **до** метрики самого
рендера. Открыто: сколько процессов на ядро — решается замером в M1.

**Риск 2. CSSOM в DOM-эмуляции.** Скрипт запекания стилей (§1.3) требует `document.styleSheets`,
`CSSStyleSheet`, `cssRules[i].cssText`. В настоящем браузере это есть; в jsdom / happy-dom — в
разной степени. Механизм смягчения: путь A′ выбран именно так, чтобы этот риск не стоял на
критическом пути, а Node проверялся отдельным замером в M2 — и проверялся **сравнением
получившегося HTML с браузерным**, а не фактом «не упало». Тест, который смотрит только на код
ответа, тут пройдёт при полностью потерянных стилях.

**Риск 3. Апстрим выкатывает `renderToString` и обесценивает часть работы.** Обесценит он ровно
одну часть — реализацию рендерера за швом D2. Механизм смягчения — сам шов; проверка того, что шов
настоящий, — вторая реализация рендерера (M2), потому что интерфейс с одной реализацией не
доказывает ничего.

**Риск 4. Bus factor Kobweb равен единице (§1.4).** Механизм смягчения: ни одной правки в апстрим
на критическом пути. Всё через `KobwebServerPlugin` и свой Gradle-плагин. Проверка: должно
собираться и работать на неизменённом опубликованном Kobweb — это условие приёмки M1, а не
пожелание.

**Риск 5. Плагины конфигурируются после `configureRouting` (§1.4).** Гипотеза: маршрут,
зарегистрированный плагином, выигрывает у catch-all `get("{...}")` по специфичности Ktor,
независимо от порядка регистрации. **Не проверено.** Если гипотеза неверна, понадобится
перехватчик на более раннем этапе конвейера Ktor вместо маршрута. Адрес проверки: M1, первым
делом — потому что если это не работает, весь путь A′ упирается в апстрим и решение надо
пересматривать.

**Открытый вопрос 1. Чем сопоставлять дерево при гидратации (D5).** Аппликер видит структуру, но
не атрибуты (§1.2). Варианты: (а) сопоставлять только структуру, атрибуты перезаписывать вслепую —
дёшево, мерцания меньше, но не ноль; (б) на сервере класть в разметку разметочные маркеры и
сверяться по ним; (в) дождаться, пока `compose-html` сможет отдавать модель узла. Гипотеза: (а)
достаточно для первой версии. Решается в M3, на реальной странице, по числу перерисованных узлов.

**Открытый вопрос 2. Сколько стоит процесс Chromium против процесса Node на одну и ту же
страницу.** Ответ определяет, делается ли M2 вообще. Гипотеза: разница по памяти велика, по
задержке — мала, и при кэше решает память. Замер в M1/M2, на одной машине, одной страницей, не
менее чем по несколько прогонов на вариант — один прогон на вариант замером не является.

**Открытый вопрос 3. Нужен ли CLA для вклада в `compose-multiplatform`.** §1.5. Спросить в
`#compose-ssr` (§5).

---

## 4. Что дальше

Порядок работ и критерии приёмки — в [BACKLOG.md](../../BACKLOG.md). Раньше всего закрывается
Риск 5: если `KobwebServerPlugin` не может перехватить маршрут страницы, выбранный путь не
существует и решение D1 пересматривается целиком. Всё остальное имеет смысл только после этого.

Порядок дальше диктуется не удобством, а тем, что от чего зависит: перехват маршрута → рендер
одной страницы через браузер → замер (Риск 1, Открытый вопрос 2) → вторая реализация рендерера,
которая и есть доказательство шва D2 → передача состояния → гидратация.

---

## 5. Приложение: черновик сообщения в `#compose-ssr`

Не представление, а вопрос — три вопроса, на каждый из которых можно ответить одной строкой, и
каждый меняет чей-то план.

> Hi — I'm looking at server-side rendering for Kobweb sites and I've been reading the SSR
> exploration post. Three questions where an answer would change what I build.
>
> **1. Is `renderToString` on anyone's plate, or is the post exactly as speculative as it says?**
> I couldn't find a YouTrack issue for a JVM target of compose-html — the closest are CMP-4461
> (wasmJs for Compose HTML, open and unassigned since March 2024) and CMP-6758, which was answered
> in 2024 with "we haven't evaluated this option yet". I'm not asking for a date; I'm asking
> whether to design around it arriving or around it not arriving.
>
> **2. Would a JVM target change the public signatures of `AttrsScope` / `ElementScope` /
> `DOMScope`, or is the plan to keep them typed by `org.w3c.dom.Element`?** This is the part that
> decides whether third-party code can share source sets. Related: `html-core-jvm:1.9.3` is
> already published and is an empty jar, so a JVM source set depending on `html-core` resolves
> today and gives you nothing — worth knowing whether that's intentional.
>
> **3. On hydration, one concrete observation rather than a request.** Today attributes, inline
> styles, classes and listeners are written straight into the `org.w3c.dom.Element` inside
> `DomElementWrapper`, in the `update { }` block of `TagElement` — they never pass through the
> `Applier`. So a custom `Applier` sees `insert`/`remove`/`move` and nothing else, which means
> nobody outside compose-html can diff a composition against an existing DOM tree. If a JVM target
> is on the table anyway, routing node mutations through the `Applier` (or exposing the node model)
> would be the single change that unblocks real hydration for everyone downstream — Kobweb #113 and
> Kilua's "replace the rendered content" are the same wall.
>
> Separately, and much smaller: is a CLA required for contributions to compose-multiplatform?
> CONTRIBUTING.md doesn't mention one and I'd rather ask than find out mid-PR.

---

## Code anchors

Кода в этом репозитории пока нет: ресёрч предшествует ему. Ниже — адреса в **чужих** деревьях, по
которым проверялись факты §1. Пути даны от корня соответствующего репозитория.

| Репозиторий | Код |
|---|---|
| JetBrains/compose-multiplatform | `html/internal-html-core-runtime/src/jsMain/kotlin/org/jetbrains/compose/web/internal/runtime/DomApplier.kt` — аппликер и `DomNodeWrapper` |
| JetBrains/compose-multiplatform | `html/internal-html-core-runtime/src/jsMain/kotlin/org/jetbrains/compose/web/renderComposable.kt` — сборка композиции, зашитый `DomApplier` |
| JetBrains/compose-multiplatform | `html/core/src/jsMain/kotlin/org/jetbrains/compose/web/dom/Base.kt` — `TagElement`, `DomElementWrapper`, точка мутации мимо аппликера |
| JetBrains/compose-multiplatform | `html/core/src/jsMain/kotlin/org/jetbrains/compose/web/dom/Elements.kt` — `ElementBuilder`, `document.createElement` |
| JetBrains/compose-multiplatform | `html/core/build.gradle.kts` — объявленный `jvm()` без исходников |
| varabyte/kobweb | `backend/server-plugin/src/main/kotlin/com/varabyte/kobweb/server/plugin/KobwebServerPlugin.kt` — точка расширения |
| varabyte/kobweb | `backend/server/src/main/kotlin/com/varabyte/kobweb/server/Application.kt` — загрузка плагинов через `ServiceLoader` |
| varabyte/kobweb | `backend/server/src/main/kotlin/com/varabyte/kobweb/server/plugins/Routing.kt` — catch-all маршрут страниц |
| varabyte/kobweb | `tools/gradle-plugins/application/src/main/kotlin/com/varabyte/kobweb/gradle/application/tasks/KobwebExportTask.kt` — Playwright и запекание CSSOM |
| varabyte/kobweb | `tools/gradle-plugins/application/src/main/kotlin/com/varabyte/kobweb/gradle/application/templates/MainTemplate.kt` — «poor man's hydration» |
| varabyte/kobweb | `tools/processor-common/src/main/kotlin/com/varabyte/kobweb/project/frontend/FrontendData.kt` — таблица маршрутов на JVM |
| rjaros/kilua | `kilua/src/commonMain/kotlin/dev/kilua/core/RenderConfig.kt` — переключатель DOM/строка |
| rjaros/kilua | `kilua/src/commonMain/kotlin/dev/kilua/html/Tag.kt` — модель узла в Kotlin и `renderToStringBuilder` |
| rjaros/kilua | `modules/kilua-ssr/src/commonMain/kotlin/dev/kilua/ssr/GlobalSsrRouter.kt` — спин-лок вокруг рендера |
| rjaros/kilua | `modules/kilua-ssr-server/src/jvmMain/kotlin/dev/kilua/ssr/SsrEngine.kt` — один Node-процесс, кэш |
| codeyousef/summon | `summon-core/src/commonMain/kotlin/codes/yousef/summon/annotation/Composable.kt` — своя аннотация, не `compose-runtime` |
