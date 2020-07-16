const HtmlWebPackPlugin = require('html-webpack-plugin')

module.exports = {
  target: 'node',
  entry: './dist/index.js',
  output: {
    filename: 'main.js'
  },
  module: {
    rules: [
      {
        test: /\.html$/,
        use: [
          {
            loader: 'html-loader',
            options: { minimize: true }
          }
        ]
      }
    ]
  },
  plugins: [
    new HtmlWebPackPlugin({
      template: './src/index.html',
      filename: './index.html'
    })
  ]
}
