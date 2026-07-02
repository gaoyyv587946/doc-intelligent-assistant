import React from 'react';

const SearchView = ({ 
  searchQuery, 
  setSearchQuery, 
  searchResult, 
  searchLoading, 
  searchError, 
  analyzerSubTab, 
  setAnalyzerSubTab, 
  handleAnalyze, 
  expandedBm25, 
  toggleBm25Expand, 
  tuneK1, 
  setTuneK1, 
  tuneB, 
  setTuneB, 
  tuneResult, 
  tuneLoading, 
  handleTune 
}) => {
  return (
    <div className="knowledge-container">
      <div className="doc-section-header">
        <h2>检索分析器</h2>
      </div>

      <div className="analyzer-input">
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="输入查询词，查看检索过程..."
          onKeyDown={(e) => e.key === 'Enter' && handleAnalyze()}
        />
        <button className="btn-primary" onClick={handleAnalyze} disabled={searchLoading}>
          {searchLoading ? '分析中...' : '分析'}
        </button>
      </div>

      {searchError && <div className="status-message error">{searchError}</div>}

      {searchResult && (
        <div className="analyzer-result">
          <div className="analyzer-tabs">
            <button className={`analyzer-tab ${analyzerSubTab === 'rrf' ? 'active' : ''}`} onClick={() => setAnalyzerSubTab('rrf')}>RRF 融合</button>
            <button className={`analyzer-tab ${analyzerSubTab === 'bm25' ? 'active' : ''}`} onClick={() => setAnalyzerSubTab('bm25')}>BM25 详解</button>
            <button className={`analyzer-tab ${analyzerSubTab === 'tune' ? 'active' : ''}`} onClick={() => setAnalyzerSubTab('tune')}>BM25 调参</button>
          </div>

          {analyzerSubTab === 'rrf' && (
            <>
              {/* 第一步：查询分析 */}
              <div className="analyzer-section">
                <h4>📋 查询分析</h4>
                <div className="query-info">
                  <div><strong>原始查询:</strong> {searchResult.query}</div>
                  <div><strong>重写查询:</strong> {(searchResult.rewrittenQueries || []).join('、')}</div>
                  {searchResult.intent && (
                    <div><strong>意图识别:</strong> {searchResult.intent.type || '未知'} - {searchResult.intent.description || ''}</div>
                  )}
                </div>
              </div>

              {/* 第二步：融合权重配置 */}
              <div className="analyzer-section">
                <h4>⚙️ 融合权重配置</h4>
                <div className="weight-info">
                  <div className="weight-item">
                    <span className="weight-label">向量权重:</span>
                    <span className="weight-value">{searchResult.weightAnalysis?.finalVectorWeight?.toFixed(4) || '1.0'}</span>
                  </div>
                  <div className="weight-item">
                    <span className="weight-label">BM25权重:</span>
                    <span className="weight-value">{searchResult.weightAnalysis?.finalKeywordWeight?.toFixed(4) || '1.0'}</span>
                  </div>
                  <div className="weight-item">
                    <span className="weight-label">RRF K值:</span>
                    <span className="weight-value">{searchResult.config?.rrfK || 60}</span>
                  </div>
                </div>
              </div>

              {/* 第三步：向量检索结果 */}
              <div className="analyzer-section">
                <h4>🔍 第一步：向量检索 <span className="formula-icon" title="余弦相似度: cosine(a,b) = a·b / (|a|*|b|)">?</span></h4>
                <p className="section-desc">使用向量相似度检索，返回与查询最相关的文档片段</p>
                <div className="result-list">
                  {(searchResult.vectorResults || []).map((r, i) => (
                    <div key={i} className="result-item">
                      <span className="result-rank">#{r.rank || i + 1}</span>
                      <div className="result-content">
                        <span className="result-title">{r.title || r.docId}</span>
                        <span className="result-preview">{r.preview}</span>
                      </div>
                      <span className="result-score vector-score">{Number(r.cosineScore || 0).toFixed(4)}</span>
                    </div>
                  ))}
                </div>
              </div>

              {/* 第四步：BM25检索结果 */}
              <div className="analyzer-section">
                <h4>🔍 第二步：BM25关键词检索 <span className="formula-icon" title="BM25 = IDF * tf_norm&#10;IDF = log((N - n + 0.5) / (n + 0.5) + 1)&#10;tf_norm = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * |D|/avgdl))">?</span></h4>
                <p className="section-desc">使用BM25算法进行关键词匹配，返回与查询关键词最相关的文档</p>
                <div className="result-list">
                  {(searchResult.keywordResults || []).map((r, i) => (
                    <div key={i} className="result-item">
                      <span className="result-rank">#{r.rank || i + 1}</span>
                      <div className="result-content">
                        <span className="result-title">{r.title || r.docId}</span>
                        <span className="result-preview">{r.preview}</span>
                      </div>
                      <span className="result-score keyword-score">{Number(r.bm25Score || 0).toFixed(4)}</span>
                    </div>
                  ))}
                </div>
              </div>

              {/* 第五步：RRF融合过程 */}
              <div className="analyzer-section">
                <h4>⚡ 第三步：RRF融合计算 <span className="formula-icon" title="RRF = weight / (K + rank + 1)&#10;两路分数相加，排名越前分数越高">?</span></h4>
                <p className="section-desc">将两路检索结果通过RRF算法融合，得到最终的综合得分</p>
                
                <div className="rrf-process">
                  <div className="rrf-formula-display">
                    <div className="formula-step">
                      <span className="step-label">计算公式:</span>
                      <span className="formula">vectorRRF = vectorWeight / (K + vectorRank + 1)</span>
                    </div>
                    <div className="formula-step">
                      <span className="step-label"></span>
                      <span className="formula">keywordRRF = keywordWeight / (K + keywordRank + 1)</span>
                    </div>
                    <div className="formula-step">
                      <span className="step-label"></span>
                      <span className="formula">rrfTotal = vectorRRF + keywordRRF</span>
                    </div>
                  </div>
                </div>

                <table className="fusion-table">
                  <thead>
                    <tr>
                      <th>文档</th>
                      <th>向量排名</th>
                      <th>BM25排名</th>
                      <th>向量RRF</th>
                      <th>BM25 RRF</th>
                      <th>RRF总分</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(searchResult.fusionResults || []).map((r, i) => (
                      <tr key={i}>
                        <td className="doc-title" title={r.docId}>{r.title || r.docId}</td>
                        <td className="rank-cell">{r.vectorRank ?? <span className="na">—</span>}</td>
                        <td className="rank-cell">{r.keywordRank ?? <span className="na">—</span>}</td>
                        <td className="score-cell">{Number(r.vectorRRF || 0).toFixed(6)}</td>
                        <td className="score-cell">{Number(r.keywordRRF || 0).toFixed(6)}</td>
                        <td className="total-cell">{Number(r.rrfTotal || 0).toFixed(6)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div className="formula-block">
                  <div className="formula-title">💡 计算说明</div>
                  <div className="formula-line">向量权重 = {Number(searchResult.weightAnalysis?.finalVectorWeight || 1.0).toFixed(4)}</div>
                  <div className="formula-line">BM25权重 = {Number(searchResult.weightAnalysis?.finalKeywordWeight || 1.0).toFixed(4)}</div>
                  <div className="formula-line">K = {searchResult.config?.rrfK || 60}</div>
                  <div className="formula-line">排名越靠前，RRF分数越高（分母越小）</div>
                </div>
              </div>

              {/* 第六步：Metadata Boosting */}
              {searchResult.boostedResults?.length > 0 && (
                <div className="analyzer-section">
                  <h4>🚀 第四步：Metadata Boosting <span className="formula-icon" title="基于标题/参数名/API路径的 token 覆盖率计算&#10;finalScore = rrfTotal * boostFactor">?</span></h4>
                  <p className="section-desc">根据文档标题、参数名、API路径等元数据的匹配程度进行加权</p>
                  
                  <table className="fusion-table">
                    <thead>
                      <tr><th>文档</th><th>RRF分数</th><th>Boost系数</th><th>最终分数</th></tr>
                    </thead>
                    <tbody>
                      {searchResult.boostedResults.map((r, i) => (
                        <tr key={i}>
                          <td className="doc-title">{r.title || r.docId}</td>
                          <td className="score-cell">{Number(r.rrfTotal || 0).toFixed(6)}</td>
                          <td className={r.boostFactor > 1 ? 'boosted' : ''}>{Number(r.boostFactor || 1).toFixed(4)}</td>
                          <td className="total-cell">{Number(r.finalScore || 0).toFixed(6)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="formula-block">
                    <div className="formula-title">💡 Boost 系数计算</div>
                    <div className="formula-line">boost = max(标题匹配加分, 参数名匹配加分, API路径匹配加分)</div>
                    <div className="formula-line">标题加分 = 1.0 + (1.5 - 1.0) * 覆盖率 (仅覆盖率 &gt; 0.5 时生效)</div>
                    <div className="formula-line">参数加分 = 1.0 + (1.3 - 1.0) * min(匹配数/总数 * 2, 1.0)</div>
                    <div className="formula-line">路径加分 = 1.0 + (1.4 - 1.0) * 覆盖率</div>
                  </div>
                </div>
              )}

              {/* 最终结果 */}
              <div className="analyzer-section">
                <h4>✅ 最终返回结果</h4>
                <p className="section-desc">综合以上所有步骤，返回得分最高的文档片段</p>
                <div className="final-list">
                  {(searchResult.finalResults || []).map((r, i) => (
                    <div key={i} className="final-item">
                      <span className="final-rank">#{i + 1}</span>
                      <div className="final-content">
                        <span className="final-title">{r.docId}</span>
                        <span className="final-preview">{r.preview}</span>
                      </div>
                      <span className="final-score">{Number(r.score || 0).toFixed(6)}</span>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}

          {analyzerSubTab === 'bm25' && (
            <div className="bm25-explain-section">
              <div className="bm25-formula-info">
                <div className="formula-title">BM25 公式</div>
                <div className="formula-line">score(D, Q) = &Sigma; IDF(q<sub>i</sub>) &times; tf_norm(q<sub>i</sub>, D)</div>
                <div className="formula-line">IDF = log((N - n + 0.5) / (n + 0.5) + 1)</div>
                <div className="formula-line">tf_norm = (tf &times; (k1 + 1)) / (tf + k1 &times; (1 - b + b &times; |D|/avgdl))</div>
                <div className="formula-line">当前参数: k1 = 1.5, b = 0.4</div>
              </div>
              {(searchResult.bm25Explain || []).length === 0 ? (
                <div className="empty-state"><p>无 BM25 命中结果</p></div>
              ) : (
                <div className="bm25-doc-list">
                  {(searchResult.bm25Explain || []).map((doc, i) => (
                    <div key={i} className="bm25-doc-card">
                      <div className="bm25-doc-header" onClick={() => toggleBm25Expand(i)}>
                        <span className="bm25-doc-rank">#{i + 1}</span>
                        <span className="bm25-doc-title">{doc.docId}</span>
                        <span className="bm25-doc-score">{Number(doc.totalScore || 0).toFixed(4)}</span>
                        <span className="bm25-expand-icon">{expandedBm25[i] ? '▼' : '▶'}</span>
                      </div>
                      {expandedBm25[i] && (
                        <div className="bm25-doc-detail">
                          <div className="bm25-stats">
                            <span>Lucene分数: {Number(doc.luceneScore || 0).toFixed(4)}</span>
                            <span>BM25计算分: {Number(doc.bm25CalculatedScore || 0).toFixed(4)}</span>
                            <span>文档长度: {doc.docLength}</span>
                            <span>平均长度: {doc.avgDocLength?.toFixed(0)}</span>
                            <span>总文档数: {doc.totalDocs}</span>
                          </div>
                          <div className="bm25-terms">
                            {(doc.terms || []).map((t, j) => (
                              <div key={j} className="bm25-term-row">
                                <span className="term-name">{t.term}</span>
                                <span className="term-idf">IDF: {Number(t.idf || 0).toFixed(4)}</span>
                                <span className="term-tf">TF: {t.tf}</span>
                                <span className="term-tfnorm">tf_norm: {Number(t.tfNorm || 0).toFixed(4)}</span>
                                <span className="term-contrib">贡献: {Number(t.contribution || 0).toFixed(6)}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {analyzerSubTab === 'tune' && (
            <div className="tune-section">
              <div className="tune-form">
                <div className="tune-input-group">
                  <label>k1:</label>
                  <input type="number" value={tuneK1} onChange={(e) => setTuneK1(Number(e.target.value))} step="0.1" min="0" max="3" />
                </div>
                <div className="tune-input-group">
                  <label>b:</label>
                  <input type="number" value={tuneB} onChange={(e) => setTuneB(Number(e.target.value))} step="0.05" min="0" max="1" />
                </div>
                <button className="btn-primary" onClick={handleTune} disabled={tuneLoading}>
                  {tuneLoading ? '测试中...' : '运行测试'}
                </button>
              </div>
              {tuneResult && tuneResult.tunedResults && (
                <div className="tune-result">
                  <div className="tune-comparison">
                    <div className="tune-column">
                      <h5>调优结果 (k1={tuneResult.k1}, b={tuneResult.b})</h5>
                      <div className="tune-results-list">
                        {tuneResult.tunedResults.map((r, i) => (
                          <div key={i} className="tune-result-item">
                            <span className="tune-rank">#{i + 1}</span>
                            <span className="tune-doc">{r.docId}</span>
                            <span className="tune-score">{Number(r.score || 0).toFixed(4)}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                    <div className="tune-column">
                      <h5>默认结果 (k1=1.5, b=0.4)</h5>
                      <div className="tune-results-list">
                        {(tuneResult.defaultResults || []).map((r, i) => (
                          <div key={i} className="tune-result-item">
                            <span className="tune-rank">#{i + 1}</span>
                            <span className="tune-doc">{r.docId}</span>
                            <span className="tune-score">{Number(r.score || 0).toFixed(4)}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {!searchResult && !searchLoading && !searchError && (
        <div className="empty-state">
          <p>输入查询词，查看检索过程</p>
          <p>将展示向量检索、BM25检索、RRF融合的完整过程</p>
        </div>
      )}
    </div>
  );
};

export default SearchView;
