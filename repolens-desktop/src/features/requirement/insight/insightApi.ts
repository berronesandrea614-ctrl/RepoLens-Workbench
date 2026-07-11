import { http } from "../../../api/http";
import { RequirementInsightVO } from "./insightTypes";

/** Fetch the requirement insight aggregated VO. */
export async function fetchRequirementInsight(
  repoId: number,
  reqId: number,
): Promise<RequirementInsightVO> {
  return (await http.get(
    `/api/repos/${repoId}/requirements/${reqId}/insight`,
  )) as unknown as RequirementInsightVO;
}
