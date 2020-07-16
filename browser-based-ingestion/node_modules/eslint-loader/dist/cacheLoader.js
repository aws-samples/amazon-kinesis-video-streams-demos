"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = cacheLoader;

var _package = require("../package.json");

var _cache = _interopRequireDefault(require("./cache"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function cacheLoader(linter, content, map) {
  const {
    loaderContext,
    options,
    CLIEngine
  } = linter;
  const callback = loaderContext.async();
  const cacheIdentifier = JSON.stringify({
    'eslint-loader': _package.version,
    eslint: CLIEngine.version
  });
  (0, _cache.default)({
    cacheDirectory: options.cache,
    cacheIdentifier,
    cacheCompression: true,
    options,
    source: content,

    transform() {
      return linter.lint(content);
    }

  }).then(res => {
    try {
      linter.printOutput({ ...res,
        src: content
      });
    } catch (error) {
      return callback(error, content, map);
    }

    return callback(null, content, map);
  }).catch(err => {
    // istanbul ignore next
    return callback(err);
  });
}