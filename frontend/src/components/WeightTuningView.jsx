import React from 'react';

const WeightTuningView = ({ 
  wtResult, 
  wtLoading, 
  wtError, 
  wtCustomWeights, 
  wtK, 
  setWtCustomWeights, 
  setWtK, 
  handleRunWeightTuning 
}) => {
  return (
    <div className="knowledge-container">
      <div className="doc-section-header">
        <h2>权重调优实验</h2>
      </div>

      <div className="wt-description">
        <p>使用评估数据集测试不同 RRF 权重组合，找到最优的向量/关键词权重比例。</p>
      </div>

      <div className="wt-controls">
        <div className="wt-input-group">
          <label>自定义权重（可选，格式: vector/keyword 例如 0.7/0.3,多个使用逗号分割）</label>
          <input type="text" value={wtCustomWeights} onChange={(e) => setWtCustomWeights(e.target.value)} placeholder="留空则自动测试多组权重" />
        </div>
        <div className="wt-input-group">
          <label>Recall@K</label>
          <input type="number" value={wtK} onChange={(e) => setWtK(parseInt(e.target.value) || 5)} min="1" max="20" />
        </div>
        <button className="btn-primary" onClick={handleRunWeightTuning} disabled={wtLoading}>
          {wtLoading ? '实验运行中...' : '运行实验'}
        </button>
      </div>

      {wtError && <div className="status-message error">{wtError}</div>}

      {wtLoading && (
        <div className="empty-state">
          <p>正在运行权重调优实验，请稍候...</p>
          <p>这可能需要几分钟时间</p>
        </div>
      )}

      {wtResult && (
        <div className="wt-result">
          <div className="wt-best-section">
            <h4>推荐权重组合</h4>
            <div className="wt-best-card">
              <div className="wt-best-header">
                <span className="wt-best-badge">最优</span>
                <span className="wt-best-ratio">{wtResult.bestCombination.ratio}</span>
              </div>
              <div className="wt-best-weights">
                <div className="wt-weight-item">
                  <span>向量权重:</span>
                  <span className="wt-weight-value">{wtResult.bestCombination.vectorWeight}</span>
                </div>
                <div className="wt-weight-item">
                  <span>关键词权重:</span>
                  <span className="wt-weight-value">{wtResult.bestCombination.keywordWeight}</span>
                </div>
              </div>
              <div className="wt-best-metrics">
                <div className="wt-metric">
                  <span className="wt-metric-label">Recall@{wtResult.k}</span>
                  <span className="wt-metric-value">{(wtResult.bestCombination['recallAt' + wtResult.k] ?? 0).toFixed(4)}</span>
                </div>
                <div className="wt-metric">
                  <span className="wt-metric-label">MRR</span>
                  <span className="wt-metric-value">{(wtResult.bestCombination.mrr ?? 0).toFixed(4)}</span>
                </div>
                <div className="wt-metric">
                  <span className="wt-metric-label">P@1</span>
                  <span className="wt-metric-value">{(wtResult.bestCombination['p@1'] ?? 0).toFixed(4)}</span>
                </div>
                <div className="wt-metric">
                  <span className="wt-metric-label">NDCG</span>
                  <span className="wt-metric-value">{(wtResult.bestCombination.ndcg ?? 0).toFixed(4)}</span>
                </div>
              </div>
            </div>
          </div>

          {wtResult.recommendation && (
            <div className="wt-recommendation">
              <span className="wt-rec-icon">💡</span>
              <span>{wtResult.recommendation}</span>
            </div>
          )}

          <div className="wt-table-section">
            <h4>全部实验结果 ({wtResult.allResults?.length || 0} 组)</h4>
            <table className="wt-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Vector 权重</th>
                  <th>Keyword 权重</th>
                  <th>比例</th>
                  <th>Recall@{wtResult.k}</th>
                  <th>P@1</th>
                  <th>MRR</th>
                  <th>NDCG</th>
                </tr>
              </thead>
              <tbody>
                {(wtResult.allResults || []).map((r, i) => {
                  const isBest = i === 0;
                  return (
                    <tr key={i} className={isBest ? 'wt-row-best' : ''}>
                      <td className="wt-rank">
                        {isBest ? <span className="wt-best-badge">BEST</span> : i + 1}
                      </td>
                      <td className="wt-weight">{r.vectorWeight}</td>
                      <td className="wt-weight">{r.keywordWeight}</td>
                      <td className="wt-ratio">{r.ratio}</td>
                      <td className="wt-metric-cell">{(r['recallAt' + wtResult.k] ?? 0).toFixed(4)}</td>
                      <td className="wt-metric-cell">{(r['p@1'] ?? 0).toFixed(4)}</td>
                      <td className="wt-metric-cell">{(r.mrr ?? 0).toFixed(4)}</td>
                      <td className="wt-metric-cell">{(r.ndcg ?? 0).toFixed(4)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {wtResult.bestCombination?.typeStats && Object.keys(wtResult.bestCombination.typeStats).length > 0 && (
            <div className="wt-type-stats">
              <h4>按查询类型分组 (最优权重)</h4>
              <div className="wt-type-grid">
                {Object.entries(wtResult.bestCombination.typeStats).map(([type, stats]) => (
                  <div key={type} className="wt-type-card">
                    <div className="wt-type-name">{type}</div>
                    <div className="wt-type-count">{stats.count} 条</div>
                    <div className="wt-type-metrics">
                      <div className="wt-type-metric">
                        <span>Recall</span>
                        <span>{(stats.recall ?? 0).toFixed(4)}</span>
                      </div>
                      <div className="wt-type-metric">
                        <span>P@1</span>
                        <span>{(stats['p@1'] ?? 0).toFixed(4)}</span>
                      </div>
                      <div className="wt-type-metric">
                        <span>MRR</span>
                        <span>{(stats.mrr ?? 0).toFixed(4)}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {!wtResult && !wtLoading && !wtError && (
        <div className="empty-state">
          <p>点击「运行实验」开始权重调优</p>
          <p>将使用评估数据集批量测试不同 RRF 权重组合的检索准确率</p>
        </div>
      )}
    </div>
  );
};

export default WeightTuningView;