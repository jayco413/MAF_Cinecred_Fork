#ifndef CAPI
#define CAPI
#endif

#ifdef __cplusplus
using namespace Clipper2Lib;
namespace Clipper2Lib {
class Clipper64;
class ClipperOffset;
template <typename T>
struct Point;
using Paths64 = std::vector<std::vector<Point<int64_t>>>;
}
extern "C" {
#else
#include <stdbool.h>
typedef void Clipper64;
typedef void Paths64;
#endif

CAPI int ClipType_NoClip(void);
CAPI int ClipType_Intersection(void);
CAPI int ClipType_Union(void);
CAPI int ClipType_Difference(void);
CAPI int ClipType_Xor(void);

CAPI int FillRule_EvenOdd(void);
CAPI int FillRule_NonZero(void);
CAPI int FillRule_Positive(void);
CAPI int FillRule_Negative(void);

CAPI int JoinType_Square(void);
CAPI int JoinType_Bevel(void);
CAPI int JoinType_Round(void);
CAPI int JoinType_Miter(void);

CAPI int EndType_Polygon(void);
CAPI int EndType_Joined(void);
CAPI int EndType_Butt(void);
CAPI int EndType_Square(void);
CAPI int EndType_Round(void);

CAPI Paths64* Paths64_New(void);
CAPI void Paths64_delete(Paths64* paths);
CAPI int Paths64_pathCount(Paths64* paths);
CAPI int Paths64_pathPointCount(Paths64* paths, int pathIndex);
CAPI long long* Paths64_pathPoints(Paths64* paths, int pathIndex);
CAPI void Paths64_addPath(Paths64* paths, long long* points, int pointCount);
CAPI void Paths64_addPaths64(Paths64* paths, Paths64* other);

CAPI void Clipper_Translate(Paths64* inPaths, long long tx, long long ty, Paths64* outPaths);
CAPI void Clipper_Transform(Paths64* inPaths, int shift, long long m00, long long m10, long long m01, long long m11, long long m02, long long m12, Paths64* outPaths);
CAPI void Clipper_BooleanOp(Paths64* inClosedSubjectPaths, Paths64* inOpenSubjectPaths, Paths64* inClipPaths, int clipType, int fillRule, bool preserveCollinear, bool reverseSolution, Paths64* outClosedPaths, Paths64* outOpenPaths);
CAPI void Clipper_Offset(Paths64* inPaths, double delta, int joinType, int endType, double miterLimit, double arcTolerance, bool preserveCollinear, bool reverseSolution, Paths64* outPaths);
CAPI Paths64* Clipper_IntersectClosedWithRect(Paths64* paths, long long left, long long top, long long right, long long bottom);
CAPI Paths64* Clipper_IntersectOpenWithRect(Paths64* paths, long long left, long long top, long long right, long long bottom);
CAPI Paths64* Clipper_Simplify(Paths64* paths, double epsilon, bool isClosed);

#ifdef __cplusplus
}
#endif
