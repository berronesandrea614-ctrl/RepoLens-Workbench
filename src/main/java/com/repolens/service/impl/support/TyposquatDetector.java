package com.repolens.service.impl.support;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Typo-squat（抢注）本地检测器，纯本地零网络。
 * <p>
 * 算法：
 * <ol>
 *   <li>对候选包名归一化（小写 + 去除 -_. 分隔符）。</li>
 *   <li>与内置热门包名清单逐一计算 <b>Damerau-Levenshtein</b> 距离（支持换位）。</li>
 *   <li>距离 ≤ 2 且候选包名本身 <b>不在</b>热门清单 → 判定为 TYPOSQUAT，
 *       返回最近的热门包名作为建议。</li>
 * </ol>
 * 此设计来自 SpellBound (arXiv:2003.03471) 的非对称守卫思路，即使离线也能命中大量抢注模式。
 */
@Component
public class TyposquatDetector {

    /** npm 热门包名（100 条精简版，覆盖高频被抢注目标）。 */
    private static final Set<String> POPULAR_NPM = Set.of(
            "react", "vue", "angular", "lodash", "axios", "express", "webpack", "babel",
            "typescript", "jest", "prettier", "eslint", "mocha", "chai", "moment", "dayjs",
            "uuid", "crypto-js", "chalk", "commander", "yargs", "inquirer", "dotenv", "cors",
            "body-parser", "cookie-parser", "jsonwebtoken", "bcrypt", "nodemailer", "socket.io",
            "passport", "mongoose", "sequelize", "redis", "pg", "mysql2", "knex", "prisma",
            "typeorm", "webpack-cli", "vite", "rollup", "esbuild", "parcel", "next", "nuxt",
            "svelte", "preact", "rxjs", "mobx", "redux", "zustand", "recoil", "jotai",
            "immer", "immutable", "ramda", "underscore", "jquery", "bootstrap", "tailwindcss",
            "sass", "less", "styled-components", "emotion", "graphql", "apollo", "node-fetch",
            "got", "superagent", "request", "cheerio", "puppeteer", "playwright", "cypress",
            "sinon", "nock", "supertest", "pm2", "nodemon", "ts-node", "source-map", "debug",
            "winston", "bunyan", "pino", "morgan", "passport-local", "passport-jwt", "multer",
            "sharp", "jimp", "pdf-lib", "pdfkit", "exceljs", "xlsx", "csv-parse", "csv-writer",
            "lodash-es", "date-fns", "luxon", "numeral", "chart.js", "d3", "three", "pixi.js",
            "socket.io-client", "ws", "ioredis", "bull", "agenda", "node-cron", "cron",
            "compression", "helmet", "rate-limiter-flexible", "express-rate-limit", "joi",
            "yup", "zod", "class-validator", "class-transformer", "reflect-metadata", "tsyringe",
            "inversify", "awilix", "fastify", "koa", "hapi", "restify", "micro",
            "left-pad", "is-array", "is-string", "is-buffer", "is-nan", "is-finite",
            "once", "inherits", "minimatch", "glob", "rimraf", "mkdirp", "ncp", "copy",
            "semver", "nopt", "resolve", "browserify", "concat-stream"
    );

    /** PyPI 热门包名（100 条精简版）。 */
    private static final Set<String> POPULAR_PYPI = Set.of(
            "requests", "numpy", "pandas", "matplotlib", "scipy", "scikit-learn", "tensorflow",
            "keras", "torch", "Pillow", "opencv-python", "flask", "django", "fastapi",
            "sqlalchemy", "celery", "redis", "pymongo", "boto3", "paramiko", "cryptography",
            "pytest", "click", "tqdm", "beautifulsoup4", "lxml", "aiohttp", "httpx",
            "pydantic", "attrs", "rich", "typer", "loguru", "black", "isort", "flake8",
            "mypy", "pylint", "bandit", "setuptools", "wheel", "pip", "virtualenv",
            "jupyter", "ipython", "notebook", "seaborn", "plotly", "bokeh", "streamlit",
            "gradio", "transformers", "diffusers", "langchain", "openai", "tiktoken",
            "faiss-cpu", "chromadb", "pymysql", "psycopg2-binary", "alembic",
            "mongoengine", "peewee", "tortoise-orm", "starlette", "uvicorn", "gunicorn",
            "werkzeug", "jinja2", "markupsafe", "itsdangerous", "wtforms", "marshmallow",
            "PyYAML", "toml", "python-dotenv", "arrow", "pendulum", "python-dateutil",
            "pytz", "humanize", "orjson", "ujson", "msgpack", "grpcio", "protobuf",
            "nats-py", "pika", "kombu", "botocore", "s3transfer", "awscli",
            "google-cloud-storage", "google-auth", "azure-storage-blob", "azure-identity",
            "pyarrow", "dask", "polars", "xarray", "sympy", "networkx", "nltk", "spacy",
            "gensim", "scikit-image", "imageio", "pytesseract", "pyaudio",
            "pyyaml", "tomlkit", "configparser", "cerberus", "voluptuous", "jsonschema",
            "hypothesis", "mock", "factory-boy", "faker", "freezegun", "responses",
            "coverage", "codecov", "tox", "nox", "pre-commit", "sphinx", "mkdocs"
    );

    /**
     * 最大判定距离：候选与热门包名归一化后距离 ≤ MAX_DISTANCE → TYPOSQUAT。
     */
    private static final int MAX_DISTANCE = 2;

    /**
     * 检测 npm 包名是否疑似 typo-squat。
     *
     * @param packageName 候选 npm 包名
     * @return Optional.empty() = 未命中；否则包含建议的正确包名
     */
    public Optional<String> detectNpm(String packageName) {
        if (packageName == null || packageName.isBlank()) return Optional.empty();
        return detect(packageName, POPULAR_NPM);
    }

    /**
     * 检测 PyPI 包名是否疑似 typo-squat。
     *
     * @param packageName 候选 PyPI 包名（可已做 PEP 503 归一化）
     * @return Optional.empty() = 未命中；否则包含建议的正确包名
     */
    public Optional<String> detectPypi(String packageName) {
        if (packageName == null || packageName.isBlank()) return Optional.empty();
        return detect(packageName, POPULAR_PYPI);
    }

    private Optional<String> detect(String candidate, Set<String> popular) {
        String normCandidate = normalize(candidate);
        int candidateLen = normCandidate.length();

        // Skip typo detection entirely for very short names (len <= 2) to avoid false positives.
        // e.g. "ky" (a real npm HTTP client, len=2) would otherwise be distance-2 from "ws".
        if (candidateLen <= 2) return Optional.empty();

        // Scale the maximum allowed edit distance with candidate length to reduce false positives
        // on short names that happen to be close to popular 2-3 char packages (pg, ws, koa, vue…):
        //   len 3-4 → only distance-1 (single-char typos)
        //   len >= 5 → distance-2 (MAX_DISTANCE, standard check)
        int effectiveMaxDist = (candidateLen >= 5) ? MAX_DISTANCE : 1;

        // 若候选本身就在热门清单 → 不是 typosquat
        for (String p : popular) {
            if (normalize(p).equals(normCandidate)) return Optional.empty();
        }

        // 找距离最近的热门包
        String bestMatch = null;
        int bestDist = Integer.MAX_VALUE;
        for (String p : popular) {
            int dist = damerauLevenshtein(normCandidate, normalize(p));
            if (dist < bestDist) {
                bestDist = dist;
                bestMatch = p;
            }
        }

        if (bestMatch != null && bestDist <= effectiveMaxDist) {
            return Optional.of(bestMatch);
        }
        return Optional.empty();
    }

    /**
     * 归一化包名：小写 + 去除 -_. 分隔符，使 "left_pad"/"left.pad" 与 "left-pad" 等价。
     * 此归一化仅用于距离计算，不影响实际包名。
     */
    public static String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("[_\\-.]", "");
    }

    /**
     * 计算两个字符串的 Damerau-Levenshtein 距离（含换位操作）。
     * 纯函数，无副作用，可直接单测。
     * <p>
     * 时间复杂度：O(|a| × |b|)；对包名长度（≤ 80）完全可接受。
     *
     * @param a 字符串 a
     * @param b 字符串 b
     * @return 最小编辑距离（插入/删除/替换/相邻字符换位各计 1）
     */
    public static int damerauLevenshtein(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;

        // dp[i][j] = a[0..i-1] 到 b[0..j-1] 的距离
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;

        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = min3(
                        dp[i - 1][j] + 1,          // deletion
                        dp[i][j - 1] + 1,          // insertion
                        dp[i - 1][j - 1] + cost    // substitution
                );
                // Transposition
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    dp[i][j] = Math.min(dp[i][j], dp[i - 2][j - 2] + cost);
                }
            }
        }
        return dp[la][lb];
    }

    private static int min3(int a, int b, int c) {
        return Math.min(a, Math.min(b, c));
    }
}
