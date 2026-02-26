/**
 * Curated icon exports from Ant Design and FontAwesome 6
 * This file provides direct imports (no barrel imports) and helper functions
 * to safely retrieve icons by name with fallback support.
 */

// ============================================================================
// ANT DESIGN ICONS - Direct imports (NO barrel imports)
// ============================================================================
import {
  // Common/Status Icons
  QuestionCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,

  // Action Icons
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  CopyOutlined,
  DownloadOutlined,
  UploadOutlined,
  ExportOutlined,
  ImportOutlined,
  SearchOutlined,
  SettingOutlined,

  // Arrow Icons
  ArrowLeftOutlined,
  ArrowRightOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  DownOutlined,

  // Navigation/UI Icons
  MenuOutlined,
  HomeOutlined,
  UserOutlined,
  PoweroffOutlined,

  // VCS Icons
  GithubOutlined,
  GitlabOutlined,

  // Organization/Team Icons
  BankOutlined,
  TeamOutlined,

  // File/Data Icons
  FileJpgOutlined,
  CloudOutlined,
  CloudServerOutlined,
  CloudUploadOutlined,

  // Status/Progress Icons
  ClockCircleOutlined,
  LoadingOutlined,
  SyncOutlined,
  PlayCircleOutlined,
  StopOutlined,

  // Other Icons
  CheckOutlined,
  CloseOutlined,
  UnorderedListOutlined,
  TagOutlined,
  LinkOutlined,
  RollbackOutlined,
  AppstoreOutlined,
  VerticalAlignBottomOutlined,
  CodeOutlined,
  CommentOutlined,
  CrownOutlined,
  EyeOutlined,
  HourglassOutlined,
  LockOutlined,
  UnlockOutlined,
  ProfileOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  BarsOutlined,
} from "@ant-design/icons";

// ============================================================================
// FONTAWESOME 6 ICONS - Direct imports (NO barrel imports)
// ============================================================================
import {
  FaHouse,
  FaUser,
  FaGear,
  FaMagnifyingGlass,
  FaPlus,
  FaTrash,
  FaPencil,
  FaDownload,
  FaUpload,
  FaLink,
  FaGithub,
  FaGitlab,
  FaCheck,
  FaXmark,
  FaTriangleExclamation,
  FaCircleInfo,
  FaCircleQuestion,
  FaArrowLeft,
  FaArrowRight,
  FaArrowUp,
  FaArrowDown,
  FaCopy,
  FaEye,
  FaEyeSlash,
  FaLock,
  FaUnlock,
  FaCloud,
  FaServer,
  FaDatabase,
  FaTerminal,
  FaCode,
  FaFile,
  FaFolder,
  FaFolderOpen,
  FaChevronDown,
  FaChevronUp,
  FaChevronLeft,
  FaChevronRight,
  FaEllipsis,
  FaEllipsisVertical,
  FaSpinner,
  FaCircleNotch,
  FaArrowsRotate,
  FaClock,
  FaCalendar,
  FaBell,
  FaEnvelope,
  FaPhone,
  FaLocationDot,
  FaHeart,
  FaStar,
  FaAsterisk,
  FaAws,
  FaGoogle,
  FaDocker,
  FaSlack,
  FaDiscord,
  FaTwitter,
  FaLinkedin,
  FaFacebook,
  FaBuilding,
} from "react-icons/fa6";

// ============================================================================
// FALLBACK ICON
// ============================================================================
export const FallbackIcon = QuestionCircleOutlined;

// ============================================================================
// ANT DESIGN ICON MAP
// ============================================================================
export const antdIcons: Record<string, React.ComponentType<any>> = {
  // Common/Status Icons
  QuestionCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,

  // Action Icons
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  CopyOutlined,
  DownloadOutlined,
  UploadOutlined,
  ExportOutlined,
  ImportOutlined,
  SearchOutlined,
  SettingOutlined,

  // Arrow Icons
  ArrowLeftOutlined,
  ArrowRightOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  DownOutlined,

  // Navigation/UI Icons
  MenuOutlined,
  HomeOutlined,
  UserOutlined,
  PoweroffOutlined,

  // VCS Icons
  GithubOutlined,
  GitlabOutlined,

  // Organization/Team Icons
  BankOutlined,
  TeamOutlined,

  // File/Data Icons
  FileJpgOutlined,
  CloudOutlined,
  CloudServerOutlined,
  CloudUploadOutlined,

  // Status/Progress Icons
  ClockCircleOutlined,
  LoadingOutlined,
  SyncOutlined,
  PlayCircleOutlined,
  StopOutlined,

  // Other Icons
  CheckOutlined,
  CloseOutlined,
  UnorderedListOutlined,
  TagOutlined,
  LinkOutlined,
  RollbackOutlined,
  AppstoreOutlined,
  VerticalAlignBottomOutlined,
  CodeOutlined,
  CommentOutlined,
  CrownOutlined,
  EyeOutlined,
  HourglassOutlined,
  LockOutlined,
  UnlockOutlined,
  ProfileOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  BarsOutlined,
};

// ============================================================================
// FONTAWESOME ICON MAP
// ============================================================================
export const faIcons: Record<string, React.ComponentType<any>> = {
  FaHouse,
  FaUser,
  FaGear,
  FaMagnifyingGlass,
  FaPlus,
  FaTrash,
  FaPencil,
  FaDownload,
  FaUpload,
  FaLink,
  FaGithub,
  FaGitlab,
  FaCheck,
  FaXmark,
  FaTriangleExclamation,
  FaCircleInfo,
  FaCircleQuestion,
  FaArrowLeft,
  FaArrowRight,
  FaArrowUp,
  FaArrowDown,
  FaCopy,
  FaEye,
  FaEyeSlash,
  FaLock,
  FaUnlock,
  FaCloud,
  FaServer,
  FaDatabase,
  FaTerminal,
  FaCode,
  FaFile,
  FaFolder,
  FaFolderOpen,
  FaChevronDown,
  FaChevronUp,
  FaChevronLeft,
  FaChevronRight,
  FaEllipsis,
  FaEllipsisVertical,
  FaSpinner,
  FaCircleNotch,
  FaArrowsRotate,
  FaClock,
  FaCalendar,
  FaBell,
  FaEnvelope,
  FaPhone,
  FaLocationDot,
  FaHeart,
  FaStar,
  FaAsterisk,
  FaAws,
  FaGoogle,
  FaDocker,
  FaSlack,
  FaDiscord,
  FaTwitter,
  FaLinkedin,
  FaFacebook,
  FaBuilding,
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Get an Ant Design icon by name
 * Returns the icon component if found, otherwise returns FallbackIcon
 *
 * @param name - The name of the icon (e.g., "QuestionCircleOutlined")
 * @returns The icon component or FallbackIcon if not found
 */
export function getIcon(name: string): React.ComponentType<any> {
  return antdIcons[name] || FallbackIcon;
}

/**
 * Get a FontAwesome icon by name
 * Returns the icon component if found, otherwise returns FallbackIcon
 *
 * @param name - The name of the icon (e.g., "FaHome")
 * @returns The icon component or FallbackIcon if not found
 */
export function getFaIcon(name: string): React.ComponentType<any> {
  return faIcons[name] || FallbackIcon;
}

// ============================================================================
// RE-EXPORTS FOR CONVENIENCE
// ============================================================================
// Export all Ant Design icons
export {
  QuestionCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  CopyOutlined,
  DownloadOutlined,
  UploadOutlined,
  ExportOutlined,
  ImportOutlined,
  SearchOutlined,
  SettingOutlined,
  ArrowLeftOutlined,
  ArrowRightOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  DownOutlined,
  MenuOutlined,
  HomeOutlined,
  UserOutlined,
  PoweroffOutlined,
  GithubOutlined,
  GitlabOutlined,
  BankOutlined,
  TeamOutlined,
  FileJpgOutlined,
  CloudOutlined,
  CloudServerOutlined,
  CloudUploadOutlined,
  ClockCircleOutlined,
  LoadingOutlined,
  SyncOutlined,
  PlayCircleOutlined,
  StopOutlined,
  CheckOutlined,
  CloseOutlined,
  UnorderedListOutlined,
  TagOutlined,
  LinkOutlined,
  RollbackOutlined,
  AppstoreOutlined,
  VerticalAlignBottomOutlined,
  CodeOutlined,
  CommentOutlined,
  CrownOutlined,
  EyeOutlined,
  HourglassOutlined,
  LockOutlined,
  UnlockOutlined,
  ProfileOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  BarsOutlined,
};

// Export all FontAwesome icons
export {
  FaHouse,
  FaUser,
  FaGear,
  FaMagnifyingGlass,
  FaPlus,
  FaTrash,
  FaPencil,
  FaDownload,
  FaUpload,
  FaLink,
  FaGithub,
  FaGitlab,
  FaCheck,
  FaXmark,
  FaTriangleExclamation,
  FaCircleInfo,
  FaCircleQuestion,
  FaArrowLeft,
  FaArrowRight,
  FaArrowUp,
  FaArrowDown,
  FaCopy,
  FaEye,
  FaEyeSlash,
  FaLock,
  FaUnlock,
  FaCloud,
  FaServer,
  FaDatabase,
  FaTerminal,
  FaCode,
  FaFile,
  FaFolder,
  FaFolderOpen,
  FaChevronDown,
  FaChevronUp,
  FaChevronLeft,
  FaChevronRight,
  FaEllipsis,
  FaEllipsisVertical,
  FaSpinner,
  FaCircleNotch,
  FaArrowsRotate,
  FaClock,
  FaCalendar,
  FaBell,
  FaEnvelope,
  FaPhone,
  FaLocationDot,
  FaHeart,
  FaStar,
  FaAsterisk,
  FaAws,
  FaGoogle,
  FaDocker,
  FaSlack,
  FaDiscord,
  FaTwitter,
  FaLinkedin,
  FaFacebook,
  FaBuilding,
};
