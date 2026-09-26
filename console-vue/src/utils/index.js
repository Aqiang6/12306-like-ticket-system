const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export const getWeekNumber = (dayIndex) => WEEK_LABELS[dayIndex] ?? ''

export const getTicketNumber = (number) => {
  if (number > 20) {
    return { color: 'text-success', label: '有' }
  }
  if (number > 0) {
    return { color: '', label: String(number) }
  }
  return { color: 'text-muted', label: '无' }
}

export const findSeatType = (seatClassList, ...types) => {
  for (const type of types) {
    const found = seatClassList?.find?.((item) => item?.type === type)
    if (found) return found
  }
  return undefined
}

export const formatDuration = (ms) => {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}
