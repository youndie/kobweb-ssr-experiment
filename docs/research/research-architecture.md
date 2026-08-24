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

Дата съёма всех фактов — **2026-08-24**. Версии, к которым они привязаны: Compose Multiplatform —
тег **v1.11.1** (последний релиз; последнее опубликованное — `1.12.0-rc01`), Kobweb — `master` на
коммите `Update Kobweb CLI version in the README to v0.9.22` (2026-08-19), Kilua — `main` на
`616b7a3` (2026-08-17).

**Правка, найденная при начале M0 (2026-08-24).** Здесь и в §1.2 было написано «1.9.3» — это
неверно: 1.9.3 не последняя версия compose-html, последний релиз 1.11.1, а последнее
опубликованное — 1.12.0-rc01. Ошибка не в поиске, а в способе чтения: список версий брался из
HTML-листинга каталога Maven Central командой `tail`, а порядок в этом листинге не тот, который
кажется, — «последние двенадцать строк» и «двенадцать самых новых версий» это разные множества.
Правильный источник — `maven-metadata.xml` с полями `<latest>` и `<release>`. Все факты §1.2
пересняты на v1.11.1 и подтвердились без изменений: `html/core/src/jsMain/.../dom/Base.kt` на теге
**побайтово совпадает** с `master`, а `html-core-jvm` пуст (599 байт) и в 1.11.1, и в 1.12.0-rc01.
То есть вывод не менялся, менялась только привязка. Правку стоит помнить не ради номера: любой
факт вида «последняя версия такая-то», снятый листингом каталога, надо перепроверять метаданными.

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
| При этом `jvm()` в таргетах объявлен, и **опубликованный `html-core-jvm` существует и пуст**: jar на 599 байт, четыре записи, ни одного класса. Проверено на 1.11.1 и на 1.12.0-rc01 — в обеих одинаково | `repo1.maven.org/maven2/org/jetbrains/compose/html/html-core-jvm/1.11.1/html-core-jvm-1.11.1.jar`; `html-core-1.11.1.module` (варианты `jvmApiElements-published`, `jvmRuntimeElements-published`) |
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
| `androidx.compose.runtime.saveable` опубликован для jvm (desktop), js(ir), androidJvm и native — то есть `SaveableStateRegistry` доступен и на сервере, и в браузере | `repo1.maven.org/.../runtime-saveable/<version>/runtime-saveable-<version>.module`; снято на 1.9.3, на текущей версии не переснималось |

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

### 1.6 M0: измерено на работающем сайте, а не выведено

Всё в этом разделе снято прогоном настоящего сайта на неизменённом опубликованном Kobweb —
фикстура в [`probe/`](../../probe/), инструкция и версии там же в
[`probe/README.md`](../../probe/README.md). Пины: Kobweb 0.25.1, Compose HTML 1.11.1, Compose
Runtime 1.12.0, Kotlin 2.4.10, Ktor 3.5.0, Gradle 9.6.1, JDK 21.

| Факт | Где проверено |
|---|---|
| Сторонний плагин действительно грузится: jar, положенный конфигурацией `kobwebServerPlugin`, попадает в `.kobweb/server/plugins` и находится `ServiceLoader`'ом — в логе `[ssr-probe] plugin loaded: dev.kobwebssr.probe.plugin.ProbeServerPlugin` | `probe/server-plugin/`, `probe/site/.kobweb/server/logs/kobweb-server.log` |
| Маршрут, которого нет ни у одной страницы, плагин обслуживает в обеих раскладках: `/ssr-probe` → 41 байт, `text/plain`, маркер плагина | прогон, `probe/README.md` |
| **`routing { get("/collide") }` в раскладке PROD/STATIC проигрывает.** Отвечает экспортированная страница: 69860 байт, маркер `RENDERED BY THE CLIENT` | тот же прогон |
| **В DEV/FULLSTACK тот же самый маршрут выигрывает**: 49 байт, маркер плагина. Разница не в плагине, а в противнике: в фулстеке страницы идут через catch-all `get("{...}")`, а в статике каждая экспортированная страница зарегистрирована точным константным маршрутом | тот же прогон |
| **Перехватчик на `ApplicationCallPipeline.Plugins` выигрывает в обеих раскладках**: `/collide2` → 58 байт, маркер плагина и в PROD/STATIC, и в DEV/FULLSTACK, при том что страница `Collide2Page` существует и экспортирована | тот же прогон |
| Непритязанный путь ведёт себя по-разному: `/nope` → 404 в статике и 1877 байт `index.html` в фулстеке | тот же прогон |
| Артефакт называется `com.varabyte.kobweb:kobweb-server-plugin`, дескриптор — `META-INF/services/com.varabyte.kobweb.server.plugin.KobwebServerPlugin`, метод — `fun configure(application: Application)` | `repo1.maven.org/.../kobweb-server-plugin/0.25.1/`; `varabyte/kobweb` на теге `v0.25.1`, `backend/server-plugin/src/main/kotlin/.../KobwebServerPlugin.kt` |
| **Три из трёх утверждений в `backend/server-plugin/README.md` устарели**: там `com.varabyte.kobweb:server-plugin` (артефакта с таким именем нет), дескриптор `...server.api.KobwebServerPlugin` (пакет `api`, а интерфейс лежит в `plugin`) и сигнатура `override fun Application.configure()` (расширение вместо параметра) | `varabyte/kobweb`, `backend/server-plugin/README.md` против `.../plugin/KobwebServerPlugin.kt` |

**Следствие 1. Риск 5 закрыт, и гипотеза в нём была неверна.** Предполагалось, что маршрут
плагина выигрывает у catch-all по специфичности Ktor «независимо от порядка регистрации». У
catch-all — выигрывает. Но в статической раскладке противник не catch-all, а **константный
маршрут той же специфичности**, и там решает порядок регистрации, а плагины конфигурируются
после `configureRouting`. То есть исходная гипотеза была верна ровно в той раскладке, где разницы
и не могло появиться.

**Следствие 2. Путь A′ жив, но точка подключения другая — перехватчик, а не `routing { }`.**
`intercept(ApplicationCallPipeline.Plugins)` отвечает раньше, чем маршрутизация вообще получает
вызов, и потому не зависит ни от раскладки, ни от порядка регистрации, ни от того, экспортирована
страница или нет. Апстрим при этом не трогается — см. правку к **D2**.

Цена перехватчика названа честно: он видит **каждый** вызов, включая статические ресурсы, и
поэтому обязан быть дешёвым на несовпадении и обязан иметь свой список путей. Это не «фильтр
по регулярке на горячем пути», это сверка с таблицей маршрутов, которая на JVM уже есть (§1.4,
следствие 3).

**Следствие 3, дорогое: `kobweb export` заражается плагином.** Экспорт водит настоящий браузер по
работающему серверу, так что всё, что плагин отдаёт во время экспорта, **запекается в статические
снапшоты**. Первый прогон этой вехи дал уверенный ложноположительный ответ именно так: `/collide`
в PROD/STATIC вернул маркер плагина — но вернул его из файла `collide.html`, в который при
экспорте попало то, как Chromium отрисовал `text/plain`-ответ плагина, вместе с
`<meta name="color-scheme">` и обёрткой `<pre>`. Файл выглядел как правдоподобная страница.
Поймано сравнением размера (205 байт вместо ожидаемых 39) — то есть числом, а не маркером.

**Механизм, чтобы это не повторилось:** плагин подключается за свойством (`-PprobePlugin`),
экспорт делается без него, и перед любым измерением проверяется, что **все** экспортированные
файлы говорят `RENDERED BY THE CLIENT`. Проверка маркером в ответе тут недостаточна по
построению: маркер не отличает «плагин ответил» от «файл, в который вчера попал ответ плагина».

---

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

### 1.7 M1: первая страница, отрендеренная на сервере

Снято тем же прогоном фикстуры, что и §1.6, теми же пинами. Рендерер — отдельный процесс
(`probe/renderer/`), плагин ходит к нему по HTTP.

| Факт | Где проверено |
|---|---|
| Страница, отрендеренная по запросу, **побайтово совпадает** с тем, что даёт `kobweb export` для неё же — `cmp` без различий, 70244 байта | прогон, `probe/renderer/`, `probe/site/.kobweb/site/ssr.html` |
| Совпадение снято там, где расхождение возможно: в раскладке DEV/FULLSTACK источник отдаёт **пустой каркас в 1877 байт** без единого маркера и без единого CSS-правила, и из него получается ровно экспорт | тот же прогон, запрос с заголовком `X-Kobweb-Ssr-Bypass` и без него |
| Правило Silk доезжает: в ответе `.ssr-marker { color: forestgreen; font-size: 1.5rem; padding: 16px; }`, в каркасе его нет | тот же прогон |
| Задержка рендера без кэша — 0.63…0.67 с на страницу, восемь запросов подряд, разброс в пределах 4 % | тот же прогон; **это не замер вехи M1-06**, а порядок величины |
| Рендерер обязан ходить на сервер с признаком обхода: его браузер запрашивает ту же страницу у того же сервера, и без обхода плагин перехватил бы собственный запрос. Проверено в обе стороны — с заголовком отдаётся каркас и рендерер не зовётся, без заголовка зовётся | `probe/server-plugin/.../ProbeServerPlugin.kt`, `probe/renderer/.../Main.kt` |
| Плагин обходится **без единой рантайм-зависимости**: HTTP-клиент взят из JDK (`java.net.http`). Причина не в аскетизме — конфигурация `kobwebServerPlugin` объявлена `isTransitive = false`, и в `.kobweb/server/plugins` приезжает ровно один jar | `varabyte/kobweb`, `KobwebApplicationPlugin.kt:91`; `probe/server-plugin/build.gradle.kts` |
| Рендерер отдал **успешный ответ на страницу с ошибкой**: когда экспортированный файл убрали, сервер ответил 500, браузер отрисовал страницу ошибки, и рендерер вернул её как валидный результат — 254 символа, HTTP 200 | тот же прогон, контрольный опыт 2 |

**Следствие 1. Критерий приёмки M1-02 в бэклоге был слабым, и это выяснилось на нём же.**
«Побайтово совпадает с экспортом» в раскладке STATIC выполняется почти тавтологически: рендерер
рендерит, запрашивая страницу у того же сервера, а там уже лежит готовый экспортированный файл —
то есть он переигрывает экспорт и получает экспорт. Ничего про пользу SSR это не говорит.
Осмысленная проверка — в FULLSTACK, где источник отдаёт каркас: вход отличается от выхода на два
порядка (1877 байт против 70244), и совпадение с экспортом становится утверждением про точность,
а не про тождество. Мерить надо было там, где расхождение возможно; в бэклоге критерий исправлен.

**Следствие 2. Обход — не деталь реализации, а часть контракта рендерера.** Конструкция замкнута
на себя по построению: перехватчик ловит запрос страницы, спрашивает рендерер, рендерер идёт
браузером на тот же адрес. Признак обхода передаётся заголовком, а не параметром запроса, чтобы
приложение видело тот URL, который просил посетитель, — у Kobweb в экспорте по той же причине
отдельный `_kobwebIsExporting`, но он читается клиентом, а этот нужен серверу.

**Следствие 3. Отказ рендерера — это отступление к Kobweb, а не ошибка посетителю.** Реализовано
так, и цена названа: снаружи выключенный SSR неотличим от исправного, поэтому отступление обязано
писать в лог. Без этой строчки «SSR работает» и «SSR молча не работает уже неделю» — одно и то же
наблюдение.

**Следствие 4. Рендерер обязан быть отдельным процессом, и решает это не архитектурный вкус.**
`kobwebServerPlugin` не транзитивна, так что всё, что плагину нужно в рантайме, либо уже лежит на
классpath сервера Kobweb, либо должно оказаться внутри его собственного jar. Playwright с его
драйвером внутрь jar не кладут. Это же ограничение делает шов **D2** честным: граница между
плагином и рендерером — граница процессов, а не спрятанная связанность.

**Следствие 5. 0.63 с на страницу без кэша — довод в пользу M1-04, а не число для отчёта.**
При такой задержке и конкурентности, ограниченной пулом (Риск 1), кэш перестаёт быть оптимизацией
и становится несущим, ровно как у Kilua с её десятью минутами (§1.5).

### 1.8 M1: кэш, пул и замер — и найденная в нём самоблокировка

Тот же стенд. Машина названа, потому что все числа ниже — про неё: MacBook Air M1, 8 ядер,
16 ГиБ, ноутбук, а не сервер; страница рендерится из DEV-сборки, где бандл 11.6 МиБ без минификации.
Переносить эти числа на прод нельзя, сравнивать варианты между собой — можно, они сняты одинаково.

| Факт | Где проверено |
|---|---|
| **Восемь параллельных запросов при выключённом кэше занимали 20.09 с**, и все восемь — ровно по 20.01 с, то есть ровно таймаут клиента. В логе рендерера при этом два рендера по 20723 мс на обоих воркерах сразу, остальные по 620–730 мс | прогон, `/tmp` не сохранялся; воспроизводится по `probe/README.md` |
| **После переноса ожидания с потока Ktor на `Dispatchers.IO` те же восемь запросов занимают 2.889 с**: четыре чистых круга по два воркера, 0.73 / 1.44 / 2.15 / 2.85 с, ни одного выброса | тот же прогон, изменена одна строка в `ProbeServerPlugin.kt` |
| Кэш: промах 0.661 с, попадания — медиана 1.8 мс на десяти запросах (мин 1.7, макс 2.2). Отношение ≈ 350× | `/ssr-probe/cache` |
| Рендер без кэша: медиана 0.632 с на десяти запросах после двух прогревочных (мин 0.624, макс 0.647) | прогон |
| Память рендерера линейна по числу воркеров: **≈ 115 МиБ постоянных + ≈ 342 МиБ на воркер**. Два круга по три конфигурации, с одинаковой историей в четыре рендера перед замером: 1 воркер — 458.2 / 455.5 МиБ, 2 — 796.3 / 800.2, 4 — 1487.7 / 1483.6 | `ps` по дереву процессов рендерера |
| Пул масштабируется, но недолинейно: восемь параллельных запросов — 2.889 с на двух воркерах против 1.71–1.86 с на четырёх, при росте одиночного рендера с ~700 до ~820 мс | прогон, `/stats` |
| Проверка результата по содержимому (**M1-07**) отбивает страницу ошибки с точной причиной — «no #_kobweb-root in the result» — и плагин отступает к Kobweb, записав это в лог с уровнем WARN. Настоящая страница при этом проходит и остаётся побайтово равной экспорту | `probe/renderer/.../RenderPool.kt`; `kobweb-server.log` |

**Следствие 1, и оно дороже остальных: рекурсия у этой конструкции не только в маршрутах, но и в
потоках.** Kobweb работает на Netty. Перехватчик ждал рендерер синхронно, прямо на потоке цикла
событий; рендерер, чтобы отрендерить страницу, идёт браузером **на тот же сервер**. Под нагрузкой
все потоки цикла событий оказываются заняты ожиданием, и обслужить запрос рендерера некому — система
ждёт собственных таймаутов. Наружу это выглядит как «медленно под нагрузкой», а не как взаимная
блокировка: код ответа 200, страницы приходят, просто через двадцать секунд.

Лечится переносом ожидания на отдельный диспетчер, и лечится полностью: 20.09 с → 2.889 с при
единственном изменении. Но помнить надо не лекарство, а форму: **любая схема, где рендерер получает
страницу от того же сервера, который его вызвал, замкнута на себя дважды** — по маршрутам (§1.7,
следствие 2) и по потокам. Второе не находится чтением кода, оно находится параллельным запросом.

**Следствие 2. Кэш спрятал бы эту находку, и это довод за порядок работ, а не против кэша.**
Взаимная блокировка проявляется только на холодном кэше под нагрузкой — то есть при выкатке, при
инвалидации, после перезапуска. Замер, сделанный только во включённой конфигурации, показал бы
1.8 мс и полную безмятежность. Отсюда правило для всех дальнейших замеров: **мерить обе позиции
рубильника**, иначе меряется кэш, а не система.

**Следствие 3. Размер пула упирается в память, а не в ядра.** 342 МиБ на воркер — это значит, что
на контейнер с 2 ГиБ помещается пять воркеров, и решение «сколько процессов» принимается по памяти.
При этом восемь ядер уже на четырёх воркерах дают недолинейный прирост: одиночный рендер дорожает с
700 до 820 мс. Обе границы надо держать в виду, и обе — про эту машину.

**Следствие 4. Открытый вопрос 2 закрыт наполовину.** Цена браузерного воркера известна: ≈342 МиБ
и ≈0.63 с на страницу. Вторая половина — та же пара чисел для Node — снимается в M2, и только их
сравнение решает, менять ли реализацию за швом.

**Правка к следствию 3 §1.5.** Там сказано, что пропускная способность инстанса равна `1 / latency`
одного рендера — это верно для Kilua, где серверное приложение одно и композиция одна. Здесь не так:
браузерных воркеров можно поставить несколько, и пропускная способность равна `N / latency` с
поправкой на конкуренцию за ядра. Ограничение Kilua — свойство её архитектуры, а не свойство SSR.

**Отброшенный замер.** Первое измерение памяти дало 744.6 МиБ на двух воркерах против 446.1 МиБ на
одном — и было выброшено: показания снимались после разного числа рендеров (двадцать шесть против
двух), то есть в варианте менялись сразу две вещи. Переснято с одинаковой историей, и разница
оказалась другой. RSS дрейфует от того, что браузер успел сделать; сравнивать можно только точки с
одинаковым прошлым.

### 1.9 M2: вторая реализация — Node под эмуляцией DOM

Стенд и машина те же, что в §1.8. Вторая реализация — `probe/node-renderer/`, тот же протокол
рендерера, что у браузерной.

**Выбор эмуляции — по измерению, а не по популярности.** Настоящий CSS этой страницы (69134
символа) прогнан через CSSOM каждой эмуляции и прочитан обратно:

| | вернулось | `@layer` | `@media` | `@keyframes` | custom props | `:hover` |
|---|---|---|---|---|---|---|
| chromium (это и есть вход) | 69134 | 271 | 24 | 2 | 258 | 27 |
| jsdom 29.1.1 | 69388 | 271 | 24 | 2 | 258 | 27 |
| happy-dom 20.11.6 | 10824 | **0** | 23 | 2 | **83** | **0** |

happy-dom теряет 84 % таблицы стилей и все каскадные слои целиком. Для Silk, у которой в `@layer`
лежит всё, это не деталь. Дальше — только jsdom.

| Факт | Где проверено |
|---|---|
| Приложение в Node **запускается и рендерит**, но для этого нужно четыре подпорки: `EventSource` (заглушка), `CSS.escape` (честная), `CSS.supports` (**догадка — всегда «да»**) и перебрасывание ошибок `URL` в realm страницы | `probe/node-renderer/index.js`, `installPolyfills` |
| Подпорка с `URL` — не косметика. jsdom бросает ошибки `URL` из realm Node, поэтому внутри страницы они **не** `instanceof Error`, и котлиновский `catch (e: Throwable)` проходит мимо. Kobweb в `Route` намеренно использует этот бросок как проверку «это относительный маршрут», так что без перебрасывания **роутер не регистрирует ни одного маршрута** | `varabyte/kobweb`, `frontend/kobweb-core/.../navigation/Route.kt:28`; стек из прогона |
| **DOM совпадает полностью**: 22 элемента, 7 под корнем Kobweb, те же 8 классов, тот же текст | `probe/node-renderer`, сравнение через CSSOM |
| **CSS не совпадает: 61 правило отсутствует** — весь слой `kobweb-compose` (`.kobweb-box`, `place-items`, `grid-area`) и стили брейкпоинтов. Два `<style>` из пяти приезжают пустыми | там же |
| Ещё 12 правил различаются только сериализацией сокращений: `outline: transparent solid 2px` против `outline: 2px solid transparent`, `border-image: initial` против `none`. Это косметика | там же |
| Правил, которые есть только у Node, — **ноль** | там же |
| Причина пустых таблиц — **не CSSOM jsdom**. Проверено напрямую: jsdom принимает `insertRule` для обычных правил, для блока и для инструкции `@layer`, для `@scope`, для `@media`, вложенный `insertRule` у группирующих правил, и приём самой compose-html — вставить пустое правило и потом менять его `.style` | прогон, изолированные пробы |
| Node нужен фиксированный «отстой»: при 500 мс результат стабилен (280 правил, 65031 байт), при 300 мс страница **выглядит готовой** (7 элементов под корнем), но правил 278, при ≤200 мс возвращается пустой каркас — и возвращается как успех | развёртка 100/200/300/500/800/1200 мс |
| Память: Node ≈445 МиБ на один одновременный рендер (два круга: 443.3 и 447.4) против ≈457 МиБ у одного браузерного воркера — **одинаково** | `ps` по дереву процессов |
| Задержка: Node 0.552 с медиана (n=8, включая 500 мс отстоя) против 0.632 с у браузера | прогон |
| **Проверка M1-07 отбивает эту реализацию сама.** Node-рендерер отказывается отдавать собственный результат — «2 empty `<style>` element(s)», плагин отступает к Kobweb и пишет причину в лог | прогон, `kobweb-server.log` |

**Следствие 1. Node не принимается, и решает это не производительность.** Памяти столько же,
задержка на 13 % меньше — и при этом теряется 61 правило, требуются четыре подпорки, из которых две
неверны по построению, и нет сигнала, которого можно дождаться. Обмен «немного быстрее в обмен на
молча неполный CSS» не выгоден ни при какой нагрузке.

**Следствие 2. Самое опасное в этой реализации — что DOM совпал.** Разметка идентична: те же
элементы, те же классы, тот же текст. Если бы проверка смотрела на структуру страницы или на код
ответа, реализация была бы принята. Расхождение видно только при сравнении CSS, и только при
сравнении через CSSOM.

**Следствие 3. Первое сравнение измеряло мой собственный разбиватель строк.** Правила делились по
закрывающей скобке, из-за чего вложенные `@layer`/`@scope` резались на куски по-разному у двух
сторон, и получалось «138 правил только у Chromium, 77 только у jsdom». После перехода на обход
CSSOM и нормализации пробелов вокруг комбинаторов класс «только у jsdom» **исчез целиком** (было
12 правил `.silk-callout`), а «только у Chromium» сжалось с 138 до 61. Различие, найденное
инструментом сравнения, — свойство инструмента, пока не доказано обратное.

**Следствие 4. Отсутствие сигнала — не мелочь реализации, а свойство подхода.** У браузера есть
события загрузки и затишья сети; у эмуляции композиция просто когда-то устаканивается. Выбирается
константа, и при 300 мс страница выглядит целой, будучи неполной. Это тот же класс, что и Риск 7:
результат, который нельзя отличить от правильного, глядя на него.

**Следствие 5. Открытый вопрос 2 закрыт.** Node дешевле не оказался: та же память, чуть меньшая
задержка, существенно худший результат. Браузер остаётся основной реализацией; Node не переводится
в основную и не удаляется — он остаётся второй реализацией шва и стендом для этого сравнения.

**Правка по итогам M5-09 (2026-08-24): числа в этом разделе были неверны, и неверны в мою пользу.**
Открытый вопрос 5 — почему два `<style>` приезжали пустыми — закрыт, и ответ оказался не про jsdom.
`document.styleSheets` — живая коллекция, а присваивание `innerHTML` элементу `<style>` пересоздаёт
его таблицу: в jsdom она при этом перемещается внутри коллекции, индексы съезжают под циклом и
записи пропускаются. Скрипт запекания обходил коллекцию **по индексу** — это дословная копия
скрипта `kobweb export`, и в Chromium коллекция не переупорядочивается, поэтому там это никогда не
проявлялось.

Измерено на одной и той же странице в один и тот же момент, менялся только способ обхода:

| обход | длины пяти `<style>` |
|---|---|
| по живому индексу | 348, **0**, 62452, **0**, 1141 |
| по снимку коллекции | 348, 3900, 62452, 1353, 1141 |

После починки — обход по снимку в обеих реализациях — сравнение с Chromium стало таким:

| | было | стало |
|---|---|---|
| записей CSS у Chromium / у jsdom | 342 / 281 | 342 / **342** |
| правил только у Chromium | **61** | **0** |
| правил только у jsdom | 0 | 0 |
| различаются сериализацией | 12 | 30 (`place-items:start` против `align-items:start;justify-items:start`) |

**То есть Node не терял CSS.** Терял его мой перенос скрипта запекания, а «61 правило» измеряло
мою ошибку, а не путь. Проверка M1-07 при этом отбивала не дефект Node — она отбивала мой дефект,
и делала это правильно; сейчас она пропускает вывод Node без возражений.

Браузерная реализация от починки не изменилась **ни на байт** — приёмка M1-02 (побайтовое
совпадение с `kobweb export`) продолжает проходить, что и подтверждает, что Chromium коллекцию не
переупорядочивает.

Решение «Node не принимается» остаётся в силе, но **основания у него теперь другие и слабее**:
не «теряет треть таблицы стилей», а «нет сигнала, которого можно дождаться» (§1.9, следствие 4),
`CSS.supports` отвечает догадкой, и памяти столько же. Это уже вопрос суждения, а не разгром;
если когда-нибудь появится способ узнать, что композиция устоялась, выбор придётся пересматривать.

Побочно: обход живой коллекции по индексу — скрытая хрупкость в собственном скрипте экспорта
Kobweb. В Chromium не проявляется, но и не гарантирована спецификацией. Третья мелочь, которую
стоит принести мейнтейнеру.

### 1.10 M3: состояние доезжает, гидратация — нет

Стенд тот же. Клиентская сторона проверялась настоящим браузером на серверно отрендеренной
странице, а не рассуждением.

| Факт | Где проверено |
|---|---|
| **`rememberSaveable` переживает границу, `remember` — нет, и оба видны на одной странице.** Сервер отрендерил `/state` со своим идентификатором экземпляра `565317`; в браузере после загрузки `saveable` показывает `saveable-from-565317` (серверный), `plain` — `plain-from-565903` (клиентский, пересчитан), `instance` — `565903` | `probe/site/.../pages/State.kt`, чтение DOM в браузере |
| В разметку уезжает **только** сохраняемое значение: `window.KOBWEB_SSR_STATE = "{\"1bgb6qms0i1v9\":[{\"t\":\"s\",\"v\":\"saveable-from-565317\"}]}"`. Значения из обычного `remember` в состоянии отсутствуют | тот же прогон |
| Состояние едет **внутри** HTML, а не рядом с ним: клиенту оно нужно до запуска бандла | `probe/renderer/.../RenderPool.kt`, `embedComposedState` |
| **Kobweb действительно сносит серверное поддерево: 11 узлов удалено, первое удаление через 312 мс** после разбора `<head>`. Итоговый корень содержит те же 7 элементов, что прислал сервер | зонд-наблюдатель, внедряемый рендерером по флагу `--instrument-hydration` |
| Гидратация «не сносить, а переписать поверх» **невозможна снаружи**: `DomNodeWrapper.insert` умеет только `insertBefore` и `appendChild`, а `factory` в `TagElement` всегда создаёт новый элемент через `elementBuilder.create()`. Композиция не усыновляет существующие узлы ни при каких условиях | `compose-multiplatform` v1.11.1, `DomApplier.kt:45`, `dom/Base.kt` |
| Сам снос живёт в **генерируемом** `main.kt`, то есть в шаблоне Gradle-плагина Kobweb, а не в чём-то, до чего дотягивается серверный плагин | `varabyte/kobweb`, `.../templates/MainTemplate.kt` |
| Маршрут страницы Kobweb берёт из **имени файла**, а не из имени функции | см. следствие 3 |

**Следствие 1. D3 подтверждено, и подтверждено вместе со своей ценой.** Перенос состояния работает
на существующем примитиве Compose, без своего сериализатора, и приносит правильную дисциплину:
через границу едет то, что автор пометил `rememberSaveable`, а остальное пересчитывается. Второе —
не дефект и не «доделаем позже»: гарантировать произвольное состояние значит сериализовать
замыкания, а это компиляторный проход, которого у Compose нет (§1.3, следствие про Qwik).

**Правка к D3.** В M1 в шов было заложено поле `RenderResult.state` — «чтобы в M3 не пришлось
менять шов». M3 им не воспользовалась: состояние обязано лежать в документе, иначе клиент не
увидит его до запуска бандла, и второй канал был бы копией. Поле убрано, а не оставлено пустым:
объявленный канал, в который никто не пишет, читается как поддерживаемая возможность. Заодно это
ответ на вопрос, чего стоит закладываться на будущее в интерфейсе — здесь угадать не удалось.

**Следствие 2. D5 неисполнимо снаружи, и это не «пока не сделали».** Настоящая гидратация требует,
чтобы композиция взяла уже стоящий узел вместо создания нового. `ElementBuilder.create()` не умеет
вернуть существующий элемент, а аппликер не умеет узнать, какой именно взять. Плюс сам снос корня
живёт в шаблоне генератора Kobweb. Обе половины лежат вне досягаемости серверного плагина, и ни
одна не обходится настройкой.

Измеренная цена того, что есть: **11 удалённых узлов и окно в 312 мс**, в течение которого
посетитель видит серверную страницу, после чего она заменяется на такую же. На странице, где
серверный и клиентский результат совпадают, это перерисовка; там, где расходятся, — моргание.
Это ровно то, что Kilua называет «hydration implemented in a simple way», и то, что мейнтейнер
Kobweb в #113 называет «not that bad of an experience» — теперь с числом.

**Следствие 3. Это усиливает просьбу в `#compose-ssr`, и меняет её адрес.** Просить надо не
`renderToString` — его и так собираются делать, и он самая лёгкая половина. Просить надо
**пропускать мутации узла через `Applier`** или дать построителю вернуть существующий элемент. Это
одна правка в `compose-html`, и она разблокирует гидратацию всем сразу: и Kobweb #113, и «replace
the rendered content» у Kilua упираются в неё же.

**Следствие 4, дешёвое, но дорого обошедшееся: маршрут берётся из имени файла.** Страница
называлась `SsrStatePage.kt` и отвечала на `/ssr-state-page`, а плагин спрашивал `/ssr-state`.
Catch-all отдал вместо неё главную, рендерер её отрендерил, вернулось 70131 байт валидного HTML —
и ни одна проверка не сработала, потому что страница была настоящая, просто не та. Проверка M1-07
такое не ловит и не должна: разметка корректна. Ловится только тем, что в отрендеренном не
оказалось ожидаемого содержимого.

**Следствие 5. Зонд, поставленный не в тот момент, показал ноль.** Первая версия наблюдателя
включалась на `DOMContentLoaded` и отчиталась «0 удалений, 0 добавлений» — а бандл Kobweb успевает
снести и перерисовать дерево **до** этого события. Ноль выглядел как «сноса нет», то есть как
хорошая новость. Наблюдатель переставлен на разбор `<head>`; и считать в нём надо **удаления**, а
не добавления: добавления делает ещё и сам разборщик HTML, поэтому число 27 в отчёте зонда
загрязнено, а 11 — нет.

**M3-02 отложена, с причиной.** Граница «серверное / интерактивное» в типах (D4) окупается только
когда по ту сторону границы что-то есть — то есть при частичной гидратации. Пока клиент в любом
случае перестраивает всё дерево целиком, такая граница описывала бы механизм, которого нет.
Проектировать API против неработающего механизма — это способ получить и API, и механизм неверными.

### 1.11 Что видно, если посмотреть на страницу, а не на её байты

Приёмка M1-03 звучала «страница читается с выключенным JavaScript и содержит стили», и до сих пор
она проверялась `grep`'ом по телу ответа. Проверено глазами: серверный ответ сохранён, из него
вырезаны **все** `<script>` — то есть ровно то, с чем остаётся посетитель без JavaScript и
поисковый робот, — и открыт в браузере.

| Факт | Где проверено |
|---|---|
| Без единого скрипта страница **отрисовывается полностью**: текст на месте, разметка колонки на месте, стиль Silk применён — «COMPOSED MARKER» зелёный, 1.5rem, с отступом. Отличий от живой страницы на глаз нет | скриншот `/ssr` без скриптов против живой `/ssr` |
| Тот же каркас **без** SSR и без скриптов — **пустая страница**. Ни текста, ни стилей | скриншот каркаса в 1840 байт |
| **Заголовок и описание у всех страниц одинаковые.** `<title>Kobweb SSR Probe</title>` и одна и та же `<meta name="description">` в `/ssr`, в `/state` и в пустом каркасе. Ни одного `<h1>` | разбор `<head>` трёх ответов |
| **Строка запроса посетителя теряется молча.** `/ssr?visitor=SHOULD-BE-VISIBLE` рендерится **побайтово так же**, как `/ssr`: рендерер строит свой URL и кладёт в него только флаги экспорта | `cmp` двух ответов; `RenderPool.snapshot` |
| Из запроса посетителя до рендера не доезжает **ничего**: контекст браузера получает единственный заголовок — признак обхода. Ни cookies, ни `Accept-Language`, ни авторизации | `RenderPool.kt:98`, `setExtraHTTPHeaders` |
| Ключ кэша — только путь | `RenderCache.kt:39` |

**Следствие 1. Половина того, ради чего делается SSR, ещё не сделана.** Тело страницы приезжает
готовым, а `<head>` — нет: для робота все страницы сайта выглядят одним документом с разным
содержимым. Заголовок, описание и канонический адрес — это ровно та часть, которую индексатор
читает в первую очередь. Проверка M1-07 такое не ловит и не должна: разметка корректна.

**Следствие 2, и это самый серьёзный из оставшихся дефектов: потеря строки запроса не отличима от
исправной работы.** Страница, которая читает `?q=`, будет отрендерена как страница без параметра,
отдана с кодом 200 и **положена в кэш** — после чего тот же неверный результат получат все, кто
спросит тот же путь с любыми параметрами. Симптома нет ни в логе, ни в проверке результата. Это
тот же класс, что Риск 7 и §1.9: результат, который нельзя отличить от правильного, глядя на него.

**Следствие 3. То же касается всего остального контекста запроса.** Cookies, язык, авторизация до
рендера не доезжают. Kilua честно пишет, что аутентифицированный контент не поддерживается
(§1.3); у нас это состояние не заявлено, а просто наступило — и наступило молча, что хуже.

**Следствие 4, методическое. Приёмка, проверенная `grep`'ом, проверяет присутствие подстроки, а не
свойство.** Здесь всё сошлось — стили действительно применились, — но узнать это можно было только
посмотрев. `grep` по `.ssr-marker` одинаково доволен и правилом, которое применяется, и правилом,
которое не выбирается ни одним селектором.

### 1.12 M5-01: отказ вместо молча неверной страницы, и что выяснилось при его написании

| Факт | Где проверено |
|---|---|
| Плагин теперь **отказывается** обслуживать запрос, несущий то, что рендерер выбросит, и отступает к Kobweb, назвав в логе, из-за чего: «the request carries a query string, which the renderer would drop» | `probe/server-plugin/.../ProbeServerPlugin.kt`; прогон |
| Отказ — **по видам контекста**, а не одним флагом. `/ssr` объявлена свободной от всех четырёх и рендерится при любом запросе; `/state` объявлена свободной только от языка — рендерится при `Accept-Language`, но отступает при строке запроса, cookies и учётных данных | тот же прогон, девять комбинаций |
| Настоящая навигация браузера присылает из этих четырёх **только `Accept-Language`**: `Accept`, `Accept-Encoding`, `Accept-Language`, `Connection`, `Host`, `Sec-Fetch-*`, `Upgrade-Insecure-Requests`, `User-Agent`. Ни cookies, ни `Authorization` | маршрут `/ssr-probe/echo-headers`, чтение в браузере |
| **Ни `kobweb export`, ни SSR не дают страницам разных `<head>`.** У всех четырёх экспортированных файлов — `collide.html`, `collide2.html`, `index.html`, `ssr.html` — один и тот же `<title>Kobweb SSR Probe</title>` | разбор экспорта |
| Перебором по исходникам `frontend` не нашлось ни одного места, где Kobweb выставлял бы заголовок или иные метаданные **на страницу**: `AppBlock.index` (`head`, `description`, `lang`) применяется на сборке к единственному `index.html`, обращений к `document.title` нет | `varabyte/kobweb`, `.../extensions/AppBlock.kt`, поиск по `frontend` |

**Следствие 1. Один флаг «маршрут не читает запрос» превратился бы в выключатель SSR.**
`Accept-Language` присылает практически любой браузер, поэтому маршрут, не объявленный свободным
**от языка**, не отрендерился бы ни для одного настоящего посетителя. Давление было бы одно:
объявить все маршруты свободными от всего — то есть сторож перестал бы сторожить. Разделение по
видам позволяет странице честно сказать «я не локализована», не заявляя заодно, что она не читает
собственную строку запроса. Это выяснилось при написании сторожа, а не при проектировании.

**Следствие 2. Сторож делает существующий ключ кэша корректным.** Ключ — только путь, и это верно
ровно потому, что маршрут либо объявлен свободным от того, что меняется, либо не рендерится, когда
это что-то присутствует. Без сторожа тот же ключ раздавал бы одну страницу всем вариантам запроса.

**Следствие 3, поправка к собственному чтению.** Сначала я отнёс отказы «cookies» и «credentials»
в логе к визиту браузера и чуть не записал вывод «сторож отключает SSR почти всем настоящим
посетителям». Это были хвосты моих же `curl`-проверок. Проверено маршрутом, который печатает имена
пришедших заголовков: браузер не прислал ни cookies, ни `Authorization`. Вывод был бы неверным, а
звучал бы правдоподобно — и опирался бы на строки лога, то есть на «данные».

**Следствие 4. Отсутствие `<head>` на страницу — дефект не нашего рендерера, а Kobweb.** Его
собственный статический экспорт выдаёт всем страницам один заголовок, и API, которым это можно
было бы поменять, в `frontend` нет. То есть **M5-03 — это не «подключить», а «этого не
существует»**, и значит вторая просьба к мейнтейнеру Kobweb, отдельная от просьбы к JetBrains
(§5). Для SSR это принципиально: индексатор читает `<head>` первым, и без разных заголовков
серверный рендеринг отдаёт роботу сайт из одинаковых документов.

### 1.13 M5-02: контекст запроса доезжает — и третья проверка шва, на этот раз не пустая

| Факт | Где проверено |
|---|---|
| Строка запроса доезжает: `/echo?q=hello` рендерится с `q=hello`, `?q=other` — с `q=other`. До вехи оба давали `q=<absent>` | страница `/echo`, читающая `ctx.route.queryParams` |
| Язык доезжает и **в заголовок, и в `navigator.language`**: `Accept-Language: ru` → `lang=ru`, `de-DE,de;q=0.9` → `lang=de-DE` | тот же прогон |
| Кэш перестал быть по пути: шесть различных запросов — шесть записей, повтор того же запроса — попадание. Ключ считается на `RenderRequest`, рядом с полями, от которых зависит | `/ssr-probe/cache` |
| Флаги рендерера подделать нельзя: `?q=x&_kobwebColorModeStrategy=EVIL&_kobwebIsExporting=false` доехал до рендерера как `/echo?q=x` | лог рендерера |
| Cookies и учётные данные **по-прежнему не пересылаются и по-прежнему отказываются** — с названной причиной | прогон, лог |
| Node-реализация приняла тот же расширенный протокол и ведёт себя так же | прогон против порта 7898 |

**Следствие 1. Граница проведена там, где она есть, а не где удобно.** Строка запроса и язык
пересылаются; cookies и учётные данные — нет, и это не «руки не дошли». Переслать их значит
сделать каждый рендер персональным: либо не кэшировать вовсе — 0.6 с на запрос на посетителя, —
либо кэшировать по пользователю, где один неверный ключ отдаёт чужую страницу. Kilua проводит ту
же черту и говорит об этом прямо (§1.3). Отказ остаётся честным состоянием, пока для этого нет
замысла, а не реализации.

**Следствие 2. Ключ кэша живёт на запросе, а не в кэше.** `RenderRequest.cacheKey` — свойство
того же класса, что несёт поля. Иначе следующее поле добавят в одно место и забудут в другом, и
Риск 9 вернётся через кэш: рендер станет правильным, а раздаваться будет чужой.

**Следствие 3. Флаги рендерера отбираются дважды.** Плагин выбрасывает из запроса всё на
`_kobweb*`, и рендерер всё равно ставит свои флаги **после** пользовательской части URL. Один
замок был бы достаточен ровно до первой ошибки в нём, а цена ошибки здесь — страница,
отрендеренная под чужими флагами и положенная в кэш для всех.

**Следствие 4, и это долгожданный ответ по D2. Третья проверка шва оказалась непустой.** M2
меняла реализацию за неизменным протоколом и потому доказала мало (§2, D2). Здесь изменился сам
протокол — и стоило это так:

| Где | Строк |
|---|---|
| `ProbeServerPlugin` — собрать контекст, сузить отказ | +54 |
| `RenderPool` — принять и применить | +40 |
| `node-renderer/index.js` — то же самое во второй реализации | +37 |
| `PageRenderer` — поля и ключ кэша | +22 |
| `Main` — разобрать параметры | +12 |
| `HttpSidecarRenderer` — сложить их в URL | +10 |

Ни один вызывающий за пределами этих шести файлов не тронут: страницы, кэш, перехватчик,
проверка результата, пул и его счётчики остались как были. Изменение локализовалось в «что такое
запрос на рендер» и в двух местах, которые это применяют, — то есть шов там, где заявлен. Это уже
не тавтология, как в M2, но и не окончательный ответ: третья реализация, внутрипроцессная,
по-прежнему единственная настоящая проверка.

**Следствие 5, цена второй реализации.** У браузера язык ставится одной настройкой контекста и
покрывает и заголовок, и `navigator.language`. У jsdom такого рычага нет: заголовок кладётся в
`fetch`, а `navigator.language` переопределяется отдельно — два места вместо одного и третий
повод разойтись с браузером. Это ровно та форма расхождения, что и четыре подпорки из §1.9.

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

**Вердикт M2 (2026-08-24): шов выдержал, но проверка была слабая, и засчитывать её как
доказательство нельзя.** Вторая реализация подключилась сменой одного свойства Gradle
(`-PssrRenderer=http://localhost:7898`), интерфейс `PageRenderer` не менялся ни на строчку, и
`HttpSidecarRenderer` пошёл к Node без единой правки. Но обе реализации стоят за **одним и тем же
HTTP-протоколом**, поэтому доказано, что протокол не зависит от рендерера, а не что от него не
зависит котлиновский интерфейс. Настоящая проверка — третья реализация, внутрипроцессная, где
HTTP нет вообще; до неё D2 считается принятым условно.

**Правка по итогам M0 (2026-08-24).** Здесь подразумевалось, что плагин подключается через
`routing { }`. Так нельзя: в раскладке STATIC маршрут плагина проигрывает константному маршруту
экспортированной страницы (§1.6). Работающая замена — перехватчик на
`ApplicationCallPipeline.Plugins`, который выигрывает в обеих раскладках. На выбор пути A′ это не
влияет — апстрим по-прежнему не трогается, — но меняет форму шва: SSR-обработчик подключается
до маршрутизации, а не внутри неё.

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

**Вердикт M3 (2026-08-24): неисполнимо снаружи, решение остаётся в силе как требование к
апстриму, а не как задача этого проекта.** Композиция не умеет усыновить существующий узел
(`ElementBuilder.create()` всегда создаёт новый, аппликер умеет только вставить и удалить), а сам
снос корня живёт в шаблоне генератора Kobweb. Измеренная цена текущего поведения — 11 удалённых
узлов и окно 312 мс (§1.10). Открытый вопрос 1 закрыт отрицательно: сопоставлять нечем, и вариант
«(а) сопоставлять только структуру» не существует — при непустом корне композиция не переписывает,
а вставляет рядом.

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

**Риск 5 — закрыт в M0, гипотеза опровергнута наполовину (§1.6).** Предполагалось, что маршрут
плагина выигрывает у Kobweb по специфичности Ktor независимо от порядка регистрации. У catch-all
`get("{...}")` — выигрывает; у константного маршрута экспортированной страницы в раскладке
STATIC — **проигрывает**, потому что специфичность равная и решает порядок, а плагины
конфигурируются после `configureRouting`. Замена найдена и измерена: перехватчик на
`ApplicationCallPipeline.Plugins` выигрывает в обеих раскладках. Остаточный риск переехал в
Открытый вопрос 4.

**Открытый вопрос 1 — закрыт отрицательно в M3 (§1.10).** Сопоставлять нечем, и вариант «(а)
сопоставлять только структуру» оказался несуществующим: композиция не переписывает содержимое
непустого корня, она вставляет своё рядом, потому что `DomNodeWrapper.insert` умеет только
`insertBefore` и `appendChild`, а фабрика узла всегда создаёт новый элемент. Остаётся вариант (в) —
изменение в `compose-html`, — и он переехал в просьбу к апстриму (§5).

**Открытый вопрос 2. Сколько стоит процесс Chromium против процесса Node на одну и ту же
страницу.** Ответ определяет, делается ли M2 вообще. Гипотеза: разница по памяти велика, по
задержке — мала, и при кэше решает память. Замер в M1/M2, на одной машине, одной страницей, не
менее чем по несколько прогонов на вариант — один прогон на вариант замером не является.

**Открытый вопрос 3. Нужен ли CLA для вклада в `compose-multiplatform`.** §1.5. Спросить в
`#compose-ssr` (§5).

**Открытый вопрос 4. Во что обходится перехватчик, стоящий перед всей маршрутизацией.** Он видит
каждый вызов, включая статику. Гипотеза: сверка пути с заранее построенным множеством маршрутов
стоит незаметно на фоне рендера, и узкое место останется в очереди рендереров (Риск 1). Мерить в
M1-06 вместе с остальным — отдельно, с плагином и без него, потому что выключённый рубильник
неотличим от исправной работы.

**Риск 9 — контекст запроса до рендера не доезжает (§1.11).** Закрыт для строки запроса и языка в
M5-02 (§1.13): и то и другое доезжает, ключ кэша считается по ним. Остаётся **открытым для
cookies и учётных данных**, и остаётся намеренно: такие страницы персональны, а персональный
рендер — это либо ноль кэша, либо кэш по пользователю с ценой ошибки «чужая страница». Сторож
M5-01 продолжает их отказывать, так что риск проявляется как отсутствие SSR, а не как неверная
страница.

**Риск 8 — размер пула ограничен памятью (§1.8, следствие 3).** 342 МиБ на воркер означает, что
конфигурация задаётся не числом ядер, а объёмом контейнера, и что «добавить воркеров» — это не
бесплатная ручка. Механизм смягчения: считать воркеры единицей ёмкости с самого начала (уже
сделано, M1-05), выставлять их число явно и мерить обе границы — память и конкуренцию за ядра —
на той машине, где сервис будет жить, а не на ноутбуке.

**Риск 7 — рендер страницы с ошибкой выглядит как удачный рендер (§1.7).** Браузер отрисовывает
страницу ошибки не хуже настоящей, и снапшот получается валидным HTML с кодом 200. В M1 это
воспроизвелось: 254 символа страницы 500 вернулись как результат. Механизм смягчения — проверка
результата **по содержимому**, а не по факту «не упало»: у отрендеренной страницы обязаны быть
корневой узел Kobweb и непустой набор правил в запечённых `<style>`; всё остальное — отказ и
отступление к Kobweb. Завести до того, как рендерер увидит первую настоящую страницу.

**Риск 6 — экспорт заражается плагином (§1.6, следствие 3).** Пока плагин загружен, `kobweb export`
запекает его ответы в статические снапшоты, и получившийся файл выглядит как настоящая страница.
Механизм смягчения — гейт по свойству и обязательная проверка всех экспортированных файлов на
маркер `RENDERED BY THE CLIENT` перед любым измерением. Проверка маркером в HTTP-ответе для этого
не годится: она не отличает «плагин ответил» от «файл, в который попал ответ плагина».

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
> decides whether third-party code can share source sets. Related: `html-core-jvm:1.11.1` is
> already published and is an empty jar, so a JVM source set depending on `html-core` resolves
> today and gives you nothing — worth knowing whether that's intentional.
>
> **3. On hydration, one concrete observation and one small ask.** Today attributes, inline styles,
> classes and listeners are written straight into the `org.w3c.dom.Element` inside
> `DomElementWrapper`, in the `update { }` block of `TagElement` — they never pass through the
> `Applier`. And `DomNodeWrapper.insert` only ever calls `insertBefore`/`appendChild`, while the
> factory in `TagElement` always makes a fresh element, so a composition can never adopt a node
> that is already in the document. Between them that means nobody outside compose-html can hydrate:
> the only option left is to throw the server's tree away and rebuild it.
>
> I measured what that costs on a server-rendered Kobweb page: 11 nodes removed, the first removal
> 312 ms after the head is parsed, and the replacement is byte-identical to what it replaced.
>
> So the ask is not `renderToString` — that part is already on your list and it is the easier half.
> The ask is either routing node mutations through the `Applier`, or letting `ElementBuilder`
> return an element that already exists. Either one unblocks hydration for everything downstream at
> once: Kobweb #113 and Kilua's "replace the rendered content" are the same wall.
>
> Separately, and much smaller: is a CLA required for contributions to compose-multiplatform?
> CONTRIBUTING.md doesn't mention one and I'd rather ask than find out mid-PR.

---

## Code anchors

Свой код появился в M0 — это фикстура, на которой снят §1.6. Остальные адреса ведут в **чужие**
деревья, по которым проверялись факты §1.1–§1.5; пути там даны от корня соответствующего
репозитория.

| Репозиторий | Код |
|---|---|
| kobweb-ssr (этот) | `probe/server-plugin/src/main/kotlin/dev/kobwebssr/probe/plugin/ProbeServerPlugin.kt` — обе точки подключения рядом: `routing { }` и перехватчик |
| kobweb-ssr (этот) | `probe/site/build.gradle.kts` — гейт `-PprobePlugin`, которым экспорт отделяется от прогона |
| kobweb-ssr (этот) | `probe/site/src/jsMain/kotlin/dev/kobwebssr/probe/pages/Collide.kt` — страница, за маршрут которой идёт спор |
| kobweb-ssr (этот) | `probe/README.md` — версии, порядок прогона и оплаченные грабли |
| kobweb-ssr (этот) | `probe/server-plugin/src/main/kotlin/dev/kobwebssr/probe/plugin/PageRenderer.kt` — шов D2 |
| kobweb-ssr (этот) | `probe/server-plugin/src/main/kotlin/dev/kobwebssr/probe/plugin/HttpSidecarRenderer.kt` — первая реализация шва |
| kobweb-ssr (этот) | `probe/renderer/src/main/kotlin/dev/kobwebssr/probe/renderer/Main.kt` — сайдкар на Playwright, протокол |
| kobweb-ssr (этот) | `probe/renderer/src/main/kotlin/dev/kobwebssr/probe/renderer/RenderPool.kt` — пул, счётчики очереди, запекание CSSOM, проверка результата по содержимому |
| kobweb-ssr (этот) | `probe/server-plugin/src/main/kotlin/dev/kobwebssr/probe/plugin/RenderCache.kt` — кэш и его рубильник |
| kobweb-ssr (этот) | `probe/node-renderer/index.js` — вторая реализация шва, подпорки и их честность |
| kobweb-ssr (этот) | `probe/node-renderer/README.md` — таблица покрытия CSSOM, по которой выбрана эмуляция |
| kobweb-ssr (этот) | `probe/site/src/jsMain/kotlin/dev/kobwebssr/probe/SsrState.kt` — мост к `SaveableStateRegistry` |
| kobweb-ssr (этот) | `probe/site/src/jsMain/kotlin/dev/kobwebssr/probe/pages/State.kt` — страница, на которой видно, что доезжает, а что нет |
| kobweb-ssr (этот) | `probe/site/src/jsMain/kotlin/dev/kobwebssr/probe/pages/Echo.kt` — страница, рендерящая то, что может дать только запрос |
| kobweb-ssr (этот) | `probe/site/src/jsMain/kotlin/dev/kobwebssr/probe/pages/Ssr.kt` — страница со стилем Silk, на которой это меряется |
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
