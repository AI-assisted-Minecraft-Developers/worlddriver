# Code style

Three tools check the code, and each has its own job. Their settings are in `config/`, and the build
applies them through the `worlddriver.lint` plugin in `build-logic/`. All three run as part of
`./gradlew check`.

| Tool | Checks | Files |
|---|---|---|
| Spotless | layout: indentation, wrapping, spacing, import order | every source set, the `*.gradle` files, the JavaScript, and the text files |
| Checkstyle | what a formatter cannot fix: naming, javadoc, braces, declaration order, method size | Java main sources |
| PMD | likely mistakes and wasteful code | Java main sources |

A Checkstyle or PMD finding fails `check`, and with it `build`. The reports are in each project's `build/reports/checkstyle/` and `build/reports/pmd/`.

## Layout belongs to Spotless

Do not lay code out by hand; run

```bash
./gradlew spotlessApply
```

and commit what it writes. `spotlessCheck` fails when a file is not laid out the way the formatter
would lay it out.

- Java: Palantir Java Format, with javadoc text reflowed.
- `*.gradle`: the Groovy-Eclipse formatter, indenting with four spaces (`config/spotless/greclipse.properties`).
- JavaScript under `*/src/`: Prettier, 120 columns, four-space indent, and a comma after the last item
  only in arrays and objects (`trailingComma: "es5"`). The scripts run in Rhino, which refuses a comma
  after the last parameter of a function.
- Markdown, properties, YAML, JSON, TOML, `.mcmeta`, `.cfg` and XML settings files: trailing spaces
  removed, tabs at the start of a line turned into spaces, a newline at the end. The generated method
  reference of the RPC skill is left alone.

The commits that first applied the formatters are listed in `.git-blame-ignore-revs`. To have
`git blame` look through them:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## Checkstyle

`config/checkstyle/checkstyle.xml` is Google's checks without the ones about layout, plus:

- JavaNCSS, with its default limits: a method, class or file with too many statements is reported
  (this replaced `scripts/check_source_budget.py`);
- InvalidJavadocPosition, from Google's checks: a `/** */` block that does not sit directly on a
  declaration, such as the first of two stacked blocks, is reported (this replaced
  `scripts/check_stacked_javadoc.py`);
- DeclarationOrder (fields before constructors and methods), ParameterNumber (at most 10) and
  MultipleStringLiterals (the same string written four or more times in one file).

Classes in a `mixin` package are not held to the naming checks, as mixin members carry a `modid$`
prefix by convention (`config/checkstyle/suppressions.xml`).

To silence one finding, annotate the declaration with
`@SuppressWarnings("checkstyle:<check name in lower case>")` and say in a comment why.

## PMD

`config/pmd/ruleset.xml` is PMD's quickstart set, less the rules Checkstyle or the formatter already
cover and a few not wanted here, plus a list of further rules. `CloseResource` does not ask code to
close the game's own objects (levels, the server, the client). `AvoidDollarSigns` does not apply in a
class annotated `@Mixin`.

To silence one finding, annotate the declaration with `@SuppressWarnings("PMD.<RuleName>")` and say
in a comment why.
