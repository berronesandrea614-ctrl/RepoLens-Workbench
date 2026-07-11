/** 暂存改动的审批状态：提议中 / 已应用 / 已拒绝 / 已撤销。 */
export type ChangeStatus = "PROPOSED" | "APPLIED" | "REJECTED" | "REVERTED";

/** 单个文件改动的完整前后内容，供 diff 预览、审批与撤销使用。 */
export interface FileChangeDetail {
  id: number;
  filePath: string;
  oldContent: string;
  newContent: string;
  createdAt: string;
  /** 0 = 生效中，非 0 = 已撤销。 */
  reverted: number;
  /** 审批状态；老数据可能缺省。 */
  status?: ChangeStatus;
}
