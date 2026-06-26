#include "clipper2/clipper.h"
#include "clippercapi.h"

int ClipType_NoClip(void) { return static_cast<int>(ClipType::NoClip); }
int ClipType_Intersection(void) { return static_cast<int>(ClipType::Intersection); }
int ClipType_Union(void) { return static_cast<int>(ClipType::Union); }
int ClipType_Difference(void) { return static_cast<int>(ClipType::Difference); }
int ClipType_Xor(void) { return static_cast<int>(ClipType::Xor); }

int FillRule_EvenOdd(void) { return static_cast<int>(FillRule::EvenOdd); }
int FillRule_NonZero(void) { return static_cast<int>(FillRule::NonZero); }
int FillRule_Positive(void) { return static_cast<int>(FillRule::Positive); }
int FillRule_Negative(void) { return static_cast<int>(FillRule::Negative); }

int JoinType_Square(void) { return static_cast<int>(JoinType::Square); }
int JoinType_Bevel(void) { return static_cast<int>(JoinType::Bevel); }
int JoinType_Round(void) { return static_cast<int>(JoinType::Round); }
int JoinType_Miter(void) { return static_cast<int>(JoinType::Miter); }

int EndType_Polygon(void) { return static_cast<int>(EndType::Polygon); }
int EndType_Joined(void) { return static_cast<int>(EndType::Joined); }
int EndType_Butt(void) { return static_cast<int>(EndType::Butt); }
int EndType_Square(void) { return static_cast<int>(EndType::Square); }
int EndType_Round(void) { return static_cast<int>(EndType::Round); }

Paths64* Paths64_New(void) {
    return new Paths64;
}

void Paths64_delete(Paths64* paths) {
    delete paths;
}

int Paths64_pathCount(Paths64* paths) {
    return paths->size();
}

int Paths64_pathPointCount(Paths64* paths, int pathIndex) {
    return (*paths)[pathIndex].size();
}

long long* Paths64_pathPoints(Paths64* paths, int pathIndex) {
    return reinterpret_cast<long long*>((*paths)[pathIndex].data());
}

void Paths64_addPath(Paths64* paths, long long* points, int pointCount) {
    Path64 path;
    path.reserve(pointCount);
    for (size_t i = 0; i < pointCount * 2; i += 2)
        path.emplace_back(points[i], points[i + 1]);
    paths->push_back(std::move(path));
}

void Paths64_addPaths64(Paths64* paths, Paths64* other) {
    paths->insert(paths->end(), other->begin(), other->end());
}

void Clipper_Translate(Paths64* inPaths, long long tx, long long ty, Paths64* outPaths) {
    outPaths->reserve(inPaths->size());
    for (Path64& inPath : *inPaths) {
        Path64 outPath;
        outPath.reserve(inPath.size());
        for (Point64& point : inPath)
            outPath.emplace_back(point.x + tx, point.y + ty);
        outPaths->push_back(std::move(outPath));
    }
}

void Clipper_Transform(Paths64* inPaths, int shift, long long m00, long long m10, long long m01, long long m11, long long m02, long long m12, Paths64* outPaths) {
    outPaths->reserve(inPaths->size());
    for (Path64& inPath : *inPaths) {
        Path64 outPath;
        outPath.reserve(inPath.size());
        for (Point64& point : inPath)
            outPath.emplace_back(((m00 * point.x + m01 * point.y) >> shift) + m02, ((m10 * point.x + m11 * point.y) >> shift) + m12);
        outPaths->push_back(std::move(outPath));
    }
}

void Clipper_BooleanOp(Paths64* inClosedSubjectPaths, Paths64* inOpenSubjectPaths, Paths64* inClipPaths, int clipType, int fillRule, bool preserveCollinear, bool reverseSolution, Paths64* outClosedPaths, Paths64* outOpenPaths) {
    Clipper64 clipper;
    if (inClosedSubjectPaths != nullptr && inClosedSubjectPaths->size() != 0)
        clipper.AddSubject(*inClosedSubjectPaths);
    if (inOpenSubjectPaths != nullptr && inOpenSubjectPaths->size() != 0)
        clipper.AddOpenSubject(*inOpenSubjectPaths);
    if (inClipPaths != nullptr && inClipPaths->size() != 0)
        clipper.AddClip(*inClipPaths);
    clipper.PreserveCollinear(preserveCollinear);
    clipper.ReverseSolution(reverseSolution);
    Paths64 dummyPaths1;
    Paths64 dummyPaths2;
    clipper.Execute(static_cast<ClipType>(clipType), static_cast<FillRule>(fillRule), outClosedPaths != nullptr ? *outClosedPaths : dummyPaths1, outOpenPaths != nullptr ? *outOpenPaths : dummyPaths2);
}

void Clipper_Offset(Paths64* inPaths, double delta, int joinType, int endType, double miterLimit, double arcTolerance, bool preserveCollinear, bool reverseSolution, Paths64* outPaths) {
    if (delta == 0.0) {
        *outPaths = *inPaths;
        return;
    }
    ClipperOffset offset(miterLimit, arcTolerance, preserveCollinear, reverseSolution);
    offset.AddPaths(*inPaths, static_cast<JoinType>(joinType), static_cast<EndType>(endType));
    offset.Execute(delta, *outPaths);
}

Paths64* Clipper_IntersectClosedWithRect(Paths64* paths, long long left, long long top, long long right, long long bottom) {
    return new Paths64(std::move(RectClip(Rect64(left, top, right, bottom), *paths)));
}

Paths64* Clipper_IntersectOpenWithRect(Paths64* paths, long long left, long long top, long long right, long long bottom) {
    return new Paths64(std::move(RectClipLines(Rect64(left, top, right, bottom), *paths)));
}

Paths64* Clipper_Simplify(Paths64* paths, double epsilon, bool isClosed) {
    return new Paths64(std::move(SimplifyPaths(*paths, epsilon, isClosed)));
}
