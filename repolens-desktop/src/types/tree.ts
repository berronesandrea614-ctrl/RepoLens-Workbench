export interface FileTreeNode {
  name: string;
  path: string;
  directory: boolean;
  children?: FileTreeNode[];
}
